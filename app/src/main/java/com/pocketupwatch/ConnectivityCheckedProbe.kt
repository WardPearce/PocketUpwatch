package com.pocketupwatch

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

internal data class ConnectionState(val network: Network?, val validated: Boolean)

internal interface ConnectionObservation : AutoCloseable {
    val initial: ConnectionState
    val interrupted: Boolean
    fun current(): ConnectionState
}

internal class AndroidConnectionObservation(private val manager: ConnectivityManager, private val requireInternet: Boolean) : ConnectionObservation {
    override val initial = current()
    private val changed = AtomicBoolean(false)
    override val interrupted get() = changed.get()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { if (network != initial.network) changed.set(true) }
        override fun onLost(network: Network) { if (network == initial.network) changed.set(true) }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (requireInternet && network == initial.network && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) changed.set(true)
        }
    }
    init { manager.registerDefaultNetworkCallback(callback) }
    override fun current(): ConnectionState {
        val network = manager.activeNetwork
        return ConnectionState(network, network?.let { manager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } == true)
    }
    override fun close() { manager.unregisterNetworkCallback(callback) }
}

internal class ConnectivityCheckedProbe(private val delegate: Probe, private val observe: (ServiceEntry) -> ConnectionObservation) : Probe {
    override suspend fun execute(service: ServiceEntry): ProbeResult {
        val observation = try { observe(service) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { return ProbeResult(null, "Unable to observe network connectivity") }
        return try {
            val before = observation.initial
            val skip = ConnectivityPolicy.internetSkipReason(service, before.network != null, before.validated)
            if (skip != null) return ProbeResult(null, skip)
            // Recheck after registration, and after any wait for a probe slot.
            val ready = observation.current()
            val readySkip = ConnectivityPolicy.internetSkipReason(service, ready.network != null, ready.validated)
            if (readySkip != null) return ProbeResult(null, readySkip)
            val result = delegate.execute(service)
            val after = observation.current()
            val afterSkip = ConnectivityPolicy.internetSkipReason(service, after.network != null, after.validated)
            when {
                afterSkip != null -> ProbeResult(null, afterSkip)
                result.up == false && (observation.interrupted || before.network != after.network || ready.network != after.network) -> ProbeResult(null, "Network changed during check")
                else -> result
            }
        } finally { observation.close() }
    }
}

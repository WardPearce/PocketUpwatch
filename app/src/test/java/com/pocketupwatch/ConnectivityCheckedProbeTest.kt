package com.pocketupwatch

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConnectivityCheckedProbeTest {
    private val wifi = ShadowNetwork.newInstance(1)
    private val mobile = ShadowNetwork.newInstance(2)
    private val service = ServiceEntry(name = "API", url = "https://example.com", intervalSeconds = 300)
    private val failure = ProbeResult(false, "Connection failed")
    @Test fun androidObservationDetectsReconnectAndUnregistersItsCallback() {
        val manager = ApplicationProvider.getApplicationContext<Context>().getSystemService(ConnectivityManager::class.java)
        val connectivity = shadowOf(manager)
        val active = requireNotNull(manager.activeNetwork)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        connectivity.setNetworkCapabilities(active, capabilities)
        val previous = connectivity.networkCallbacks.toSet()
        val observation = AndroidConnectionObservation(manager, true)
        try {
            assertTrue(observation.initial.validated)
            val callback = (connectivity.networkCallbacks - previous).single()
            callback.onAvailable(active)
            callback.onCapabilitiesChanged(active, capabilities)
            assertFalse("Initial callbacks must not look like a handover", observation.interrupted)
            callback.onLost(active)
            callback.onAvailable(active)
            assertTrue("Remember disconnects even after the same network returns", observation.interrupted)
        } finally { observation.close() }
        assertEquals(previous, connectivity.networkCallbacks)
    }
    private class Observation(override val initial: ConnectionState) : ConnectionObservation {
        var state = initial
        override var interrupted = false
        var closed = false
        override fun current() = state
        override fun close() { closed = true }
    }

    @Test fun offlineAndUnvalidatedConnectionsSkipWithoutProbing() = runBlocking {
        for (state in listOf(ConnectionState(null, false), ConnectionState(wifi, false))) {
            val observation = Observation(state)
            var calls = 0
            val result = ConnectivityCheckedProbe(Probe { calls++; failure }) { observation }.execute(service)
            assertNull(result.up)
            assertEquals(0, calls)
            assertTrue(observation.closed)
        }
    }

    @Test fun stableValidatedWifiAndMobileConnectionsCanReportRealFailures() = runBlocking {
        for (network in listOf(wifi, mobile)) {
            val observation = Observation(ConnectionState(network, true))
            val result = ConnectivityCheckedProbe(Probe { failure }) { observation }.execute(service)
            assertEquals(failure, result)
            assertTrue(observation.closed)
        }
    }

    @Test fun losingInternetOrValidationDuringAProbeIsSkipped() = runBlocking {
        for (after in listOf(ConnectionState(null, false), ConnectionState(wifi, false))) {
            val observation = Observation(ConnectionState(wifi, true))
            val result = ConnectivityCheckedProbe(Probe { observation.state = after; failure }) { observation }.execute(service)
            assertNull(result.up)
        }
    }

    @Test fun handoversAndDisconnectReconnectCannotBeMistakenForEndpointFailures() = runBlocking {
        for (reconnected in listOf(false, true)) {
            val observation = Observation(ConnectionState(wifi, true))
            val result = ConnectivityCheckedProbe(Probe {
                if (reconnected) observation.interrupted = true
                else observation.state = ConnectionState(mobile, true)
                failure
            }) { observation }.execute(service)
            assertNull(result.up)
            assertEquals("Network changed during check", result.detail)
        }
    }

    @Test fun lanChecksCanFailWithoutInternetWhenTheRequirementIsOff() = runBlocking {
        val observation = Observation(ConnectionState(wifi, false))
        assertEquals(failure, ConnectivityCheckedProbe(Probe { failure }) { observation }.execute(service.copy(requireValidatedInternet = false)))
    }

    @Test fun cancellationAlwaysUnregistersConnectivityObservation() = runBlocking {
        val observation = Observation(ConnectionState(wifi, true))
        val ready = CompletableDeferred<Unit>()
        val job = launch { ConnectivityCheckedProbe(Probe { ready.complete(Unit); awaitCancellation() }) { observation }.execute(service) }
        ready.await()
        job.cancelAndJoin()
        assertTrue(observation.closed)
    }

    @Test fun observationFailureIsUnknownAndDoesNotLeakExceptionMessages() = runBlocking {
        val result = ConnectivityCheckedProbe(Probe { fail("Must not probe without connectivity observation"); failure }) {
            throw SecurityException("secret")
        }.execute(service)
        assertNull(result.up)
        assertFalse(result.detail.contains("secret"))
    }

    @Test fun connectivitySkipsDoNotCreateIncidentsOrCountAsDowntime() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MonitorDb::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.dao()
            val id = dao.add(service)
            val monitor = service.copy(id = id)
            dao.recordIfUnchanged(monitor, CheckEntry(serviceId = id, time = 1_000, up = true, responseMs = 10, detail = "HTTP 200"))
            val observation = Observation(ConnectionState(wifi, true))
            val result = ConnectivityCheckedProbe(Probe { observation.state = ConnectionState(null, false); failure }) { observation }.execute(monitor)
            assertNull(dao.recordIfUnchanged(monitor, CheckEntry(serviceId = id, time = 2_000, up = result.up == true, responseMs = result.responseMs, detail = result.detail, skipped = result.up == null)))
            assertEquals("UP", dao.alertState(id)!!.confirmed)
            assertTrue(dao.incidents(id).first().isEmpty())
            assertEquals(0L, dao.stats(id, 0).first().downCount)
            assertEquals(1L, dao.stats(id, 0).first().skippedCount)
        } finally { db.close() }
    }
}

package com.pocketupwatch

import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A monitor target stored as a URI, including non-HTTP check types. */
internal data class MonitorTarget(val protocol: String, val host: String, val port: Int = -1) {
    companion object {
        fun parse(value: String): MonitorTarget? {
            val scheme = value.substringBefore("://").lowercase()
            if (scheme == "http" || scheme == "https") {
                val url = value.toHttpUrlOrNull() ?: return null
                return MonitorTarget(url.scheme, url.host, url.port)
            }
            val uri = try { URI(value) } catch (_: Exception) { return null }
            val protocol = uri.scheme?.lowercase() ?: return null
            if (protocol !in listOf("http", "https", "tcp", "ping", "tls", "dns")) return null
            val host = uri.host ?: return null
            if (host.isBlank() || uri.userInfo != null) return null
            if (uri.port !in -1..65535 || uri.port == 0) return null
            if (protocol == "tcp" && uri.port == -1) return null
            if (protocol in listOf("ping", "dns") && uri.port != -1) return null
            if (protocol in listOf("tcp", "ping", "tls", "dns") && (!uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null)) return null
            return MonitorTarget(protocol, host.removePrefix("[").removeSuffix("]"), if (protocol == "tls" && uri.port == -1) 443 else uri.port)
        }
    }
}

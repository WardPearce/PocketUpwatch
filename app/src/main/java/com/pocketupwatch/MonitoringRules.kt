package com.pocketupwatch

import com.google.gson.*
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

internal object BodyMatcher {
    private val gson = GsonBuilder().setStrictness(Strictness.STRICT).create()
    fun json(value: String): JsonElement = gson.fromJson(value, JsonElement::class.java) ?: throw IllegalArgumentException("JSON is empty")
    fun select(root: JsonElement, path: String): JsonElement {
        require(path.startsWith("$")) { "JSON path must start with $" }
        var value = root
        var position = 1
        val token = Regex("\\.([A-Za-z_][A-Za-z0-9_-]*)|\\[([0-9]+)]")
        while (position < path.length) {
            val match = token.find(path, position)?.takeIf { it.range.first == position } ?: throw IllegalArgumentException("Use paths such as $.status or $.items[0].ok")
            value = if (match.groups[1] != null) value.asJsonObject.get(match.groupValues[1]) ?: throw IllegalArgumentException("JSON path not found") else value.asJsonArray.get(match.groupValues[2].toInt())
            position = match.range.last + 1
        }
        return value
    }
    fun validate(type: String, expected: String, path: String = ""): String? = try {
        when (type) {
            "JSON" -> { json(expected); if (path.isNotBlank()) validatePath(path) }
            "XML" -> xml(expected)
            "Text", "None" -> Unit
            else -> error("Unknown body matcher")
        }
        null
    } catch (_: Exception) { "Invalid $type expectation or JSON path" }
    fun validatePath(path: String) { require(Regex("\\$(?:\\.[A-Za-z_][A-Za-z0-9_-]*|\\[[0-9]+])*").matches(path)) }
    fun matches(type: String, expected: String, actual: String, path: String = ""): Boolean = try {
        when (type) {
            "None" -> true
            "Text" -> actual == expected
            "JSON" -> (if (path.isBlank()) json(actual) else select(json(actual), path)) == json(expected)
            "XML" -> xml(actual).isEqualNode(xml(expected))
            else -> false
        }
    } catch (_: Exception) { false }
    private fun xml(value: String) = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
        isNamespaceAware = true
    }.newDocumentBuilder().parse(InputSource(StringReader(value))).apply { normalizeDocument() }
}

internal data class AlertDecision(val state: AlertState, val notification: String? = null)
internal object AlertPolicy {
    fun evaluate(old: AlertState, service: ServiceEntry, check: CheckEntry): AlertDecision {
        if (check.skipped) return AlertDecision(old.copy(downStreak = 0, upStreak = 0, candidateSince = 0))
        if (check.up) {
            val streak = old.upStreak + 1
            val recovered = old.confirmed == "DOWN" && streak >= service.recoveryThreshold
            val confirmed = if (old.confirmed != "DOWN" || recovered) "UP" else "DOWN"
            return AlertDecision(old.copy(confirmed = confirmed, downStreak = 0, upStreak = streak, candidateSince = 0, lastAlertAt = if (recovered) check.time else old.lastAlertAt), if (recovered) "recovered" else null)
        }
        val streak = old.downStreak + 1
        val since = if (old.downStreak == 0) check.time else old.candidateSince
        val down = streak >= service.failureThreshold
        val newlyDown = down && old.confirmed != "DOWN"
        val reminder = old.confirmed == "DOWN" && service.repeatMinutes > 0 && check.time - old.lastAlertAt >= service.repeatMinutes * 60_000L
        val notify = (newlyDown && (old.confirmed != "UNKNOWN" || service.notifyInitialDown)) || reminder
        return AlertDecision(old.copy(confirmed = if (down) "DOWN" else old.confirmed, downStreak = streak, upStreak = 0, candidateSince = since, lastAlertAt = if (notify || newlyDown) check.time else old.lastAlertAt), if (notify) if (reminder) "is still down" else "is down" else null)
    }
}

internal object MonitorStatus {
    fun label(service: ServiceEntry, latest: CheckEntry?, active: Boolean, now: Long): String = when {
        !service.enabled -> "PAUSED"
        service.maintenanceUntil > now -> "MAINTENANCE"
        !active -> "STOPPED"
        latest == null -> "PENDING"
        now - latest.time > maxOf(service.intervalSeconds * 2_000L, 120_000L) -> "STALE"
        latest.skipped -> "UNKNOWN"
        latest.up -> "UP"
        else -> "DOWN"
    }
}

/** Deadlines are monotonic within a process; wall time is used only when restoring after restart. */
internal object CheckSchedule {
    fun restoredDeadline(service: ServiceEntry, latest: CheckEntry?, wallNow: Long, elapsedNow: Long): Long {
        if (latest == null) return elapsedNow
        val interval = service.intervalSeconds.coerceAtLeast(300) * 1_000L
        return elapsedNow + (interval - (wallNow - latest.time).coerceAtLeast(0)).coerceIn(0, interval)
    }
    fun alarmSeconds(deadlines: Collection<Long>, elapsedNow: Long): Int? = deadlines.minOrNull()?.let { ((it - elapsedNow).coerceAtLeast(1) + 999).div(1_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
}

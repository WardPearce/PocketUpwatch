package com.pocketupwatch

import com.google.gson.*

internal object BodyMatcher {
    fun json(value: String): JsonElement = StructuredBodies.json(value)
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
    fun validate(type: String, expected: String, path: String = ""): String? {
        val problem = StructuredBodies.problem(if (type == "YML") "YAML" else type, expected)
        if (problem != null) return problem.description
        if (type == "JSON" && path.isNotBlank()) return runCatching { validatePath(path) }.exceptionOrNull()?.let { "Use a path such as $.status or $.items[0].ok." }
        return null
    }
    fun validatePath(path: String) { require(Regex("\\$(?:\\.[A-Za-z_][A-Za-z0-9_-]*|\\[[0-9]+])*" ).matches(path)) }
    fun matches(type: String, expected: String, actual: String, path: String = "", xmlStructural: Boolean = false, xmlIgnoreWhitespace: Boolean = false): Boolean = try {
        when (type) {
            "None" -> true
            "Text" -> actual == expected
            "JSON" -> StructuredBodies.equivalent(if (path.isBlank()) json(actual) else select(json(actual), path), json(expected))
            "YAML", "YML" -> StructuredBodies.equivalent(StructuredBodies.yaml(actual), StructuredBodies.yaml(expected))
            "XML" -> if (xmlStructural) StructuredBodies.xmlEquivalent(expected, actual, xmlIgnoreWhitespace) else StructuredBodies.xml(actual).isEqualNode(StructuredBodies.xml(expected))
            else -> false
        }
    } catch (_: Exception) { false }
}

internal object AlertReminders {
    const val DEFAULT_MINUTES = 60
    const val MIN_MINUTES = 5
    const val MAX_MINUTES = 10080
    fun normalize(minutes: Int): Int = if (minutes in 1 until MIN_MINUTES) MIN_MINUTES else minutes
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
        val notify = newlyDown && (old.confirmed != "UNKNOWN" || service.notifyInitialDown)
        return AlertDecision(old.copy(confirmed = if (down) "DOWN" else old.confirmed, downStreak = streak, upStreak = 0, candidateSince = since, lastAlertAt = if (notify || newlyDown) check.time else old.lastAlertAt), if (notify) "is down" else null)
    }
}

internal object MonitorStatus {
    fun confirmed(service: ServiceEntry, latest: CheckEntry?, state: AlertState?): String =
        state?.confirmed ?: when {
            latest == null || latest.skipped -> "UNKNOWN"
            latest.up -> "UP"
            service.failureThreshold == 1 -> "DOWN"
            else -> "UNKNOWN"
        }

    fun label(service: ServiceEntry, latest: CheckEntry?, active: Boolean, now: Long, state: AlertState? = null): String = when {
        !service.enabled -> "PAUSED"
        service.maintenanceUntil > now -> "MAINTENANCE"
        !active -> "STOPPED"
        latest == null -> "PENDING"
        now - latest.time > maxOf(service.intervalSeconds * 2_000L, 120_000L) -> "STALE"
        latest.skipped -> "UNKNOWN"
        else -> confirmed(service, latest, state)
    }

    fun evidence(service: ServiceEntry, latest: CheckEntry?, state: AlertState?): String = when {
        latest == null -> "Waiting for the first check"
        latest.skipped -> "Latest check skipped · ${latest.detail}"
        !latest.up && confirmed(service, latest, state) != "DOWN" ->
            "Latest check failed · ${(state?.downStreak ?: 1).coerceAtLeast(1)} of ${service.failureThreshold} failures"
        latest.up && confirmed(service, latest, state) == "DOWN" ->
            "Latest check passed · ${(state?.upStreak ?: 1).coerceAtLeast(1)} of ${service.recoveryThreshold} successes to recover"
        latest.up -> "Latest check passed · ${latest.detail}"
        else -> "Latest check failed · ${latest.detail}"
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

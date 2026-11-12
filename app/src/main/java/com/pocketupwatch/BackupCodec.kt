package com.pocketupwatch

import com.google.gson.*
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Portable backups never contain device-specific Keystore ciphertext. */
internal object BackupCodec {
    private val gson = GsonBuilder().setStrictness(Strictness.STRICT).setPrettyPrinting().create()
    private const val MAX_BYTES = 4 * 1_024 * 1_024
    private const val ITERATIONS = 210_000
    fun encode(services: List<ServiceEntry>, password: CharArray, decrypt: (String) -> Map<String, String>): String {
        val includeSecrets = password.isNotEmpty()
        require(!includeSecrets || password.size >= 12) { "Use at least 12 characters for encrypted backups" }
        val monitors = JsonArray()
        services.forEach { service ->
            val portable = service.copy(id = 0, enabled = false, headersCiphertext = "", url = if (includeSecrets) service.url else redactedExportUrl(service.url))
            val value = gson.toJsonTree(portable).asJsonObject
            if (includeSecrets) value.add("headers", gson.toJsonTree(decrypt(service.headersCiphertext)))
            if (!includeSecrets) { value.addProperty("requestBody", ""); value.addProperty("expectedBody", ""); value.addProperty("needsReview", true) }
            monitors.add(value)
        }
        val plain = gson.toJson(JsonObject().apply { addProperty("version", 1); add("monitors", monitors) })
        require(plain.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Backup is too large" }
        if (!includeSecrets) return plain
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(password, salt)) }
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return gson.toJson(JsonObject().apply {
            addProperty("format", "pocket-upwatch-encrypted-v1")
            addProperty("salt", Base64.getEncoder().encodeToString(salt))
            addProperty("iv", Base64.getEncoder().encodeToString(cipher.iv))
            addProperty("data", Base64.getEncoder().encodeToString(encrypted))
        })
    }
    fun decode(input: String, password: CharArray, encrypt: (Map<String, String>) -> String): List<ServiceEntry> {
        require(input.toByteArray(Charsets.UTF_8).size <= MAX_BYTES * 2) { "Backup is too large" }
        var root = gson.fromJson(input, JsonObject::class.java)
        if (root.has("format")) {
            require(root.get("format").asString == "pocket-upwatch-encrypted-v1") { "Unsupported backup format" }
            val salt = Base64.getDecoder().decode(root.get("salt").asString)
            val iv = Base64.getDecoder().decode(root.get("iv").asString)
            require(salt.size == 16 && iv.size == 12) { "Invalid encrypted backup" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)) }
            root = gson.fromJson(String(cipher.doFinal(Base64.getDecoder().decode(root.get("data").asString)), Charsets.UTF_8), JsonObject::class.java)
        }
        require(root.get("version")?.asInt == 1) { "Unsupported backup version" }
        val monitors = root.getAsJsonArray("monitors") ?: error("Missing monitors")
        require(monitors.size() <= 1_000) { "Too many monitors" }
        return monitors.map { element ->
            val obj = element.asJsonObject
            val service = gson.fromJson(obj, ServiceEntry::class.java)
            val headers = obj.getAsJsonObject("headers")?.entrySet()?.associate { it.key to it.value.asString } ?: emptyMap()
            val restored = service.copy(id = 0, enabled = false, headersCiphertext = encrypt(headers))
            require(ServiceValidation.error(restored, headers, allowIncompleteBody = obj.get("needsReview")?.asBoolean == true) == null) { "Invalid monitor in backup" }
            restored
        }
    }
    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") } finally { spec.clearPassword() }
    }
    private fun redactedExportUrl(value: String): String {
        val url = value.toHttpUrlOrNull() ?: return value
        val builder = url.newBuilder().username("").password("").fragment(null)
        url.queryParameterNames.forEach { builder.setQueryParameter(it, "REDACTED") }
        return builder.build().toString()
    }
    fun historyCsv(checks: List<CheckEntry>): String = buildString {
        append("time_utc_ms,state,response_ms,detail\r\n")
        checks.forEach { append("${it.time},${if (it.skipped) "UNKNOWN" else if (it.up) "UP" else "DOWN"},${it.responseMs},${csv(it.detail)}\r\n") }
    }
    fun incidentsCsv(incidents: List<Incident>): String = buildString {
        append("started_utc_ms,ended_utc_ms,down_checks,unknown_checks,reason\r\n")
        incidents.forEach { append("${it.startedAt},${it.endedAt ?: ""},${it.downChecks},${it.unknownChecks},${csv(it.reason)}\r\n") }
    }
    private fun csv(value: String): String = "\"" + (if (value.firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'" else "") + value.replace("\"", "\"\"") + "\""
}

internal object ServiceValidation {
    private fun isIp(value: String): Boolean = if (value.contains(':')) {
        Regex("[0-9a-fA-F:]+").matches(value) && runCatching { java.net.InetAddress.getByName(value) is java.net.Inet6Address }.getOrDefault(false)
    } else value.split('.').let { it.size == 4 && it.all { part -> part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255 } }
    fun error(service: ServiceEntry, headers: Map<String, String> = emptyMap(), allowIncompleteBody: Boolean = false): String? = when {
        service.name.isBlank() || service.name.length > 200 -> "Enter a name of up to 200 characters"
        MonitorTarget.parse(service.url) == null -> "Enter a valid target"
        service.intervalSeconds !in 60..86400 -> "Interval must be between 1 minute and 24 hours"
        service.timeoutSeconds !in 1..60 -> "Timeout must be between 1 and 60 seconds"
        service.expectedCode != 0 && service.expectedCode !in 100..599 -> "Choose any 2xx or an HTTP status from 100 to 599"
        service.method !in listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE") -> "Unsupported HTTP method"
        service.method == "HEAD" && service.bodyType != "None" -> "HEAD requests cannot match a body"
        service.failureThreshold !in 1..20 || service.recoveryThreshold !in 1..20 -> "Alert thresholds must be between 1 and 20"
        service.repeatMinutes !in 0..10080 -> "Reminder interval must be between 0 and 10080 minutes"
        service.dnsExpected.isNotBlank() && service.dnsExpected.split(',').any { !isIp(it.trim()) } -> "Expected DNS answers must be comma-separated IPv4 or IPv6 addresses"
        service.certificateWarnDays !in 0..365 -> "Certificate warning must be between 0 and 365 days"
        headers.any { (key, value) -> !Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+").matches(key) || value.any { it == '\r' || it == '\n' || it.code < 32 || it.code > 126 } } -> "Invalid HTTP header"
        service.requestBody.toByteArray().size > 1_048_576 || service.expectedBody.toByteArray().size > 1_048_576 -> "Bodies must not exceed 1 MiB"
        !allowIncompleteBody && BodyMatcher.validate(service.bodyType, service.expectedBody, service.jsonPath) != null -> BodyMatcher.validate(service.bodyType, service.expectedBody, service.jsonPath)
        else -> null
    }
}

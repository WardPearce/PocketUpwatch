package com.pocketupwatch

import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable fun ServiceEditor(existing: ServiceEntry?, modifier: Modifier = Modifier, onCancel: () -> Unit, onSave: (ServiceEntry) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val secrets = remember { SecretStore() }
    val loadedHeaders = remember(existing?.id) { runCatching { secrets.decrypt(existing?.headersCiphertext ?: "") } }
    val authorization = loadedHeaders.getOrDefault(emptyMap()).entries.firstOrNull { it.key.equals("Authorization", true) }?.value.orEmpty()
    var clearBrokenCredentials by remember { mutableStateOf(false) }
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var protocol by rememberSaveable(existing?.id) { mutableStateOf(existing?.url?.substringBefore("://") ?: "https") }
    var target by rememberSaveable(existing?.id) { mutableStateOf(existing?.url?.substringAfter("://") ?: "") }
    var interval by rememberSaveable(existing?.id) { mutableIntStateOf(existing?.intervalSeconds ?: 300) }
    var tags by rememberSaveable(existing?.id) { mutableStateOf(existing?.tags ?: "") }
    var method by rememberSaveable(existing?.id) { mutableStateOf(existing?.method ?: "GET") }
    var anySuccess by rememberSaveable(existing?.id) { mutableStateOf(existing?.expectedCode?.let { it == 0 } ?: true) }
    var status by rememberSaveable(existing?.id) { mutableStateOf(existing?.expectedCode?.takeIf { it != 0 }?.toString() ?: "200") }
    var bodyType by rememberSaveable(existing?.id) { mutableStateOf(existing?.bodyType ?: "None") }
    var expectedBody by remember(existing?.id) { mutableStateOf(existing?.expectedBody ?: "") }
    var jsonPath by rememberSaveable(existing?.id) { mutableStateOf(existing?.jsonPath ?: "") }
    var requestBody by remember(existing?.id) { mutableStateOf(existing?.requestBody ?: "") }
    var headersText by remember(existing?.id) { mutableStateOf(loadedHeaders.getOrDefault(emptyMap()).filterKeys { !it.equals("Authorization", true) }.entries.joinToString("\n") { "${it.key}: ${it.value}" }) }
    var authMode by remember(existing?.id) { mutableStateOf(if (authorization.startsWith("Bearer ")) "Bearer" else if (authorization.startsWith("Basic ")) "Basic" else "None") }
    val basic = remember(authorization) { runCatching { String(Base64.decode(authorization.removePrefix("Basic "), Base64.DEFAULT), Charsets.ISO_8859_1) }.getOrDefault("") }
    var username by remember(existing?.id) { mutableStateOf(if (authMode == "Basic") basic.substringBefore(':') else "") }
    var password by remember(existing?.id) { mutableStateOf(if (authMode == "Basic") basic.substringAfter(':', "") else "") }
    var bearer by remember(existing?.id) { mutableStateOf(if (authMode == "Bearer") authorization.removePrefix("Bearer ") else "") }
    var timeout by rememberSaveable(existing?.id) { mutableStateOf((existing?.timeoutSeconds ?: 20).toString()) }
    var redirects by rememberSaveable(existing?.id) { mutableStateOf(existing?.followRedirects ?: true) }
    var validated by rememberSaveable(existing?.id) { mutableStateOf(existing?.requireValidatedInternet ?: false) }
    var failures by rememberSaveable(existing?.id) { mutableStateOf((existing?.failureThreshold ?: 2).toString()) }
    var recoveries by rememberSaveable(existing?.id) { mutableStateOf((existing?.recoveryThreshold ?: 1).toString()) }
    var initialDown by rememberSaveable(existing?.id) { mutableStateOf(existing?.notifyInitialDown ?: true) }
    var repeat by rememberSaveable(existing?.id) { mutableStateOf((existing?.repeatMinutes ?: 0).toString()) }
    var maintenance by rememberSaveable(existing?.id) { mutableLongStateOf(existing?.maintenanceUntil ?: 0) }
    var warningDays by rememberSaveable(existing?.id) { mutableStateOf((existing?.certificateWarnDays ?: 30).toString()) }
    var dnsExpected by rememberSaveable(existing?.id) { mutableStateOf(existing?.dnsExpected ?: "") }
    var busy by remember { mutableStateOf(false) }
    var diagnostic by remember { mutableStateOf<ProbeResult?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val url = "$protocol://${target.trim()}"
    val http = protocol in listOf("http", "https")
    val parsedHeaders = remember(headersText, authMode, bearer, username, password, authorization) { runCatching {
        val values = linkedMapOf<String, String>()
        headersText.lineSequence().filter { it.isNotBlank() }.forEach {
            require(it.contains(':')) { "Use one Header: value per line" }
            val key = it.substringBefore(':').trim()
            require(values.keys.none { old -> old.equals(key, true) }) { "Duplicate HTTP header" }
            values[key] = it.substringAfter(':').trim()
        }
        when (authMode) {
            "Bearer" -> values["Authorization"] = "Bearer ${bearer.trim()}"
            "Basic" -> values["Authorization"] = Credentials.basic(username, password)
            else -> if (authorization.isNotBlank() && !authorization.startsWith("Basic ") && !authorization.startsWith("Bearer ")) values["Authorization"] = authorization
        }
        values.toMap()
    } }
    val draft = ServiceEntry(id = existing?.id ?: 0, name = name.trim(), url = url, intervalSeconds = interval, enabled = existing?.enabled ?: true, expectedCode = if (anySuccess) 0 else status.toIntOrNull() ?: -1, bodyType = if (http) bodyType else "None", expectedBody = expectedBody, method = if (http) method else "GET", requestBody = requestBody, jsonPath = jsonPath, timeoutSeconds = timeout.toIntOrNull() ?: 0, followRedirects = redirects, requireValidatedInternet = validated, failureThreshold = failures.toIntOrNull() ?: 0, recoveryThreshold = recoveries.toIntOrNull() ?: 0, notifyInitialDown = initialDown, repeatMinutes = repeat.toIntOrNull() ?: -1, maintenanceUntil = maintenance, certificateWarnDays = warningDays.toIntOrNull() ?: -1, dnsExpected = dnsExpected, tags = tags.trim())
    val validation = when {
        loadedHeaders.isFailure && !clearBrokenCredentials -> "Saved credentials cannot be decrypted. Clear them explicitly to replace them."
        parsedHeaders.isFailure -> parsedHeaders.exceptionOrNull()?.message ?: "Invalid headers"
        else -> remember(draft, parsedHeaders.getOrNull()) { ServiceValidation.error(draft, parsedHeaders.getOrDefault(emptyMap())) }
    }
    val prepared: suspend () -> ServiceEntry = {
        withContext(Dispatchers.IO) {
            val headers = parsedHeaders.getOrThrow().toMutableMap()
            val parsedUrl = url.toHttpUrlOrNull()
            if (parsedUrl != null && parsedUrl.username.isNotBlank() && headers.keys.none { it.equals("Authorization", true) }) headers["Authorization"] = Credentials.basic(parsedUrl.username, parsedUrl.password)
            draft.copy(url = parsedUrl?.newBuilder()?.username("")?.password("")?.build()?.toString() ?: url, headersCiphertext = secrets.encrypt(headers))
        }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            ProtocolPicker(protocol) { protocol = it }
            TextField(target, { input ->
                val pasted = input.substringBefore("://").lowercase()
                if (input.contains("://") && pasted in listOf("http", "https", "tcp", "ping", "dns", "tls")) {
                    protocol = pasted
                    target = input.substringAfter("://")
                } else target = input
            }, Modifier.weight(1f), label = { Text(if (protocol == "tcp") "Host:port" else if (protocol == "tls") "Host or host:port" else if (http) "Host, path and query" else "Host") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        }
        TextField(tags, { tags = it }, Modifier.fillMaxWidth(), label = { Text("Tags (comma separated)") })
        IntervalSlider(interval, existing?.intervalSeconds) { interval = it }
        TextField(timeout, { timeout = it }, label = { Text("Timeout in seconds (1–60)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        SwitchRow("Require validated internet", validated) { validated = it }
        Text("Leave off for private LAN targets. Without validated internet, public target failures can reflect your network.", style = MaterialTheme.typography.bodySmall)
        if (http) {
            ChoiceMenu("HTTP method", method, listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")) { method = it; if (it == "HEAD") bodyType = "None" }
            SwitchRow("Accept any 2xx status", anySuccess) { anySuccess = it }
            if (!anySuccess) TextField(status, { status = it }, label = { Text("Expected HTTP status") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            SwitchRow("Follow redirects", redirects) { redirects = it }
            ChoiceMenu("Authentication", authMode, listOf("None", "Bearer", "Basic")) { authMode = it }
            if (authMode == "Bearer") TextField(bearer, { bearer = it }, Modifier.fillMaxWidth(), label = { Text("Bearer token") }, visualTransformation = PasswordVisualTransformation())
            if (authMode == "Basic") {
                TextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("Username") })
                TextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, visualTransformation = PasswordVisualTransformation())
            }
            TextField(headersText, { headersText = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 6, label = { Text("Custom headers (Header: value)") }, visualTransformation = PasswordVisualTransformation())
            Text("Headers and credentials are encrypted on this device. They are included only in password-encrypted backups.", style = MaterialTheme.typography.bodySmall)
            if (loadedHeaders.isFailure) SwitchRow("Clear inaccessible saved credentials", clearBrokenCredentials) { clearBrokenCredentials = it }
            if (method in listOf("POST", "PUT", "PATCH")) TextField(requestBody, { requestBody = it }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 8, label = { Text("Request body") })
            if (method != "HEAD") ChoiceMenu("Response body match", bodyType, listOf("None", "Text", "JSON", "XML")) { bodyType = it }
            if (bodyType != "None") TextField(expectedBody, { expectedBody = it }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 8, label = { Text("Expected $bodyType value") })
            if (bodyType == "JSON") TextField(jsonPath, { jsonPath = it }, Modifier.fillMaxWidth(), label = { Text("JSON path (optional, e.g. $.status)") }, supportingText = { Text("Expected value must be valid JSON, e.g. \"ok\". Leave path blank to compare the full document.") })
        }
        if (protocol == "dns") TextField(dnsExpected, { dnsExpected = it }, Modifier.fillMaxWidth(), label = { Text("Expected IP addresses (optional, comma separated)") })
        if (protocol == "tls") TextField(warningDays, { warningDays = it }, Modifier.fillMaxWidth(), label = { Text("Warn when certificate expires within days") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        if (protocol == "ping") Text("Some hosts and networks block ICMP echo requests.", style = MaterialTheme.typography.bodySmall)
        Text("Alert rules", style = MaterialTheme.typography.titleLarge)
        TextField(failures, { failures = it }, label = { Text("Consecutive failures before down (1–20)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        TextField(recoveries, { recoveries = it }, label = { Text("Consecutive successes before recovery (1–20)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        SwitchRow("Alert when first observed state is down", initialDown) { initialDown = it }
        TextField(repeat, { repeat = it }, label = { Text("Repeat down alert after minutes (0 = off)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        ChoiceMenu("Maintenance", if (maintenance > System.currentTimeMillis()) "Until ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(maintenance))}" else "Off", listOf("Off", "1 hour", "4 hours", "24 hours")) { maintenance = when (it) { "1 hour" -> 1L; "4 hours" -> 4L; "24 hours" -> 24L; else -> 0L }.let { h -> if (h == 0L) 0 else System.currentTimeMillis() + h * 3_600_000 } }
        Text("Maintenance records unknown skips and suspends endpoint alerts.", style = MaterialTheme.typography.bodySmall)
        validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        diagnostic?.let { Text("${if (it.up == null) "UNKNOWN" else if (it.up) "UP" else "DOWN"} · ${it.responseMs} ms · ${it.detail}") }
        OutlinedButton(enabled = validation == null && !busy, onClick = {
            busy = true; actionError = null
            scope.launch {
                try { val service = prepared(); diagnostic = withContext(Dispatchers.IO) { ProbeEngine.runner(context).run(service) } }
                catch (_: Exception) { actionError = "Could not run the test" }
                finally { busy = false }
            }
        }) { Text(if (busy) "Working…" else "Test before saving") }
        Text("Tests make a real request, bypass scheduling and maintenance, and do not record results or send alerts.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(enabled = validation == null && !busy, onClick = {
                busy = true; actionError = null
                scope.launch { try { onSave(prepared()) } catch (_: Exception) { actionError = "Could not encrypt credentials"; busy = false } }
            }) { Text("Save") }
        }
    }
}

@Composable internal fun SwitchRow(label: String, checked: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(checked, changed) }
}

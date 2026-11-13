package com.pocketupwatch

import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable internal fun ServiceEditor(existing: ServiceEntry?, modifier: Modifier = Modifier, onCancel: () -> Unit, probe: Probe? = null, onSave: suspend (ServiceEntry) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val secrets = remember { SecretStore() }
    val loadedHeaders = remember(existing?.id) { runCatching { secrets.decrypt(existing?.headersCiphertext ?: "") } }
    val authorization = loadedHeaders.getOrDefault(emptyMap()).entries.firstOrNull { it.key.equals("Authorization", true) }?.value.orEmpty()
    var clearBrokenCredentials by remember { mutableStateOf(false) }
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var protocol by rememberSaveable(existing?.id) { mutableStateOf(existing?.url?.substringBefore("://") ?: "https") }
    var target by rememberSaveable(existing?.id) { mutableStateOf(existing?.url?.substringAfter("://") ?: "") }
    var interval by rememberSaveable(existing?.id) { mutableIntStateOf((existing?.intervalSeconds ?: 300).coerceAtLeast(300)) }
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
    var validated by rememberSaveable(existing?.id) { mutableStateOf(existing?.requireValidatedInternet ?: true) }
    var failures by rememberSaveable(existing?.id) { mutableStateOf((existing?.failureThreshold ?: 1).toString()) }
    var recoveries by rememberSaveable(existing?.id) { mutableStateOf((existing?.recoveryThreshold ?: 1).toString()) }
    var initialDown by rememberSaveable(existing?.id) { mutableStateOf(existing?.notifyInitialDown ?: true) }
    var repeat by rememberSaveable(existing?.id) { mutableStateOf((existing?.repeatMinutes ?: 0).toString()) }
    var maintenance by rememberSaveable(existing?.id) { mutableLongStateOf(existing?.maintenanceUntil ?: 0) }
    var warningDays by rememberSaveable(existing?.id) { mutableStateOf((existing?.certificateWarnDays ?: 30).toString()) }
    var dnsExpected by rememberSaveable(existing?.id) { mutableStateOf(existing?.dnsExpected ?: "") }
    var busy by remember { mutableStateOf(false) }
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
    val scroll = rememberScrollState()
    val bodyValidation = remember(bodyType, expectedBody, jsonPath) { BodyMatcher.validate(bodyType, expectedBody, jsonPath) }
    Column(modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                if (existing == null) "Start with a name and target. Adjust the other settings when you need them." else "Update your target or fine-tune how this service is monitored.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
            EditorSection("Service details", "What would you like to monitor?", Icons.Outlined.Dns) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Name") }, placeholder = { Text("e.g. Production API") }, singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProtocolPicker(protocol, enabled = !busy) { protocol = it }
                    OutlinedTextField(target, { input ->
                        val pasted = input.substringBefore("://").lowercase()
                        if (input.contains("://") && pasted in listOf("http", "https", "tcp", "ping", "dns", "tls")) {
                            protocol = pasted
                            target = input.substringAfter("://")
                        } else target = input
                    }, Modifier.weight(1f), enabled = !busy, label = { Text(if (protocol == "tcp") "Host:port" else if (protocol == "tls") "Host or host:port" else if (http) "Host, path and query" else "Host") }, placeholder = { Text(if (protocol == "tcp") "example.com:443" else "example.com") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                }
                Text(when (protocol) {
                    "tcp" -> "Checks whether a connection can be opened to this port."
                    "ping" -> "Checks host reachability. Some hosts and networks block ping."
                    "dns" -> "Resolves this host and optionally verifies its IP addresses."
                    "tls" -> "Checks certificate trust, hostname and expiry."
                    else -> "Checks the HTTP response from this address."
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            EditorSection("Monitoring", "Choose when checks run and which network they use.", Icons.Outlined.Schedule) {
                IntervalSlider(interval, existing?.intervalSeconds, enabled = !busy) { interval = it }
                HorizontalDivider()
                EditorToggle("Require Internet", "Only count failures while Android confirms Internet access over Wi-Fi, mobile data, or another network. Offline checks are skipped. Turn off for local services.", validated, !busy) { validated = it }
            }
            EditorSection("Request settings", if (http) "$method · ${timeout}s timeout" else "${timeout}s timeout", Icons.Outlined.Tune, collapsible = true, enabled = !busy) {
                if (http) EditorDropdown("HTTP method", method, listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"), !busy) { method = it; if (it == "HEAD") bodyType = "None" }
                OutlinedTextField(timeout, { timeout = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Timeout (seconds)") }, supportingText = { Text("Between 1 and 60 seconds") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (http) {
                    EditorToggle("Follow redirects", "Check the final destination when a server redirects the request.", redirects, !busy) { redirects = it }
                    if (method in listOf("POST", "PUT", "PATCH")) OutlinedTextField(requestBody, { requestBody = it }, Modifier.fillMaxWidth(), enabled = !busy, minLines = 3, maxLines = 8, label = { Text("Request body") })
                }
            }
            if (http) {
                EditorSection("Response rules", "${if (anySuccess) "Any 2xx status" else "HTTP $status"} · ${if (bodyType == "None") "No body match" else "$bodyType match"}", Icons.AutoMirrored.Outlined.FactCheck, collapsible = true, initiallyExpanded = bodyType != "None" && bodyValidation != null, enabled = !busy) {
                    EditorToggle("Accept any 2xx status", "Treat any successful HTTP status as healthy.", anySuccess, !busy) { anySuccess = it }
                    if (!anySuccess) OutlinedTextField(status, { status = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Expected HTTP status") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    if (method != "HEAD") EditorDropdown("Response body match", bodyType, listOf("None", "Text", "JSON", "XML"), !busy) { bodyType = it }
                    if (bodyType != "None") OutlinedTextField(expectedBody, { expectedBody = it }, Modifier.fillMaxWidth(), enabled = !busy, minLines = 3, maxLines = 8, isError = bodyValidation != null, label = { Text("Expected $bodyType value") })
                    if (bodyType == "JSON") OutlinedTextField(jsonPath, { jsonPath = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("JSON path (optional, e.g. $.status)") }, supportingText = { Text("Use valid JSON for the expected value, such as \"ok\". A blank path compares the full document.") })
                }
                EditorSection("Authentication & headers", if (loadedHeaders.isFailure && !clearBrokenCredentials) "Saved credentials need attention" else if (authMode == "None" && headersText.isBlank()) "No authentication or custom headers" else "$authMode authentication · ${headersText.lineSequence().count { it.isNotBlank() }} custom headers", Icons.Outlined.Lock, collapsible = true, initiallyExpanded = loadedHeaders.isFailure, enabled = !busy) {
                    EditorDropdown("Authentication", authMode, listOf("None", "Bearer", "Basic"), !busy) { authMode = it }
                    if (authMode == "Bearer") OutlinedTextField(bearer, { bearer = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Bearer token") }, visualTransformation = PasswordVisualTransformation())
                    if (authMode == "Basic") {
                        OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Username") })
                        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation())
                    }
                    OutlinedTextField(headersText, { headersText = it }, Modifier.fillMaxWidth(), enabled = !busy, minLines = 2, maxLines = 6, label = { Text("Custom headers (Header: value)") }, visualTransformation = PasswordVisualTransformation())
                    Text("Headers and credentials are encrypted on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (loadedHeaders.isFailure) EditorToggle("Clear inaccessible saved credentials", "Replace credentials that this device can no longer decrypt.", clearBrokenCredentials, !busy) { clearBrokenCredentials = it }
                }
            }
            if (protocol == "dns") EditorSection("DNS answers", if (dnsExpected.isBlank()) "Accept any resolved IP address" else "Check expected IP addresses", Icons.AutoMirrored.Outlined.FactCheck, collapsible = true, enabled = !busy) {
                OutlinedTextField(dnsExpected, { dnsExpected = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Expected IP addresses") }, supportingText = { Text("Optional. Separate IPv4 or IPv6 addresses with commas.") })
            }
            if (protocol == "tls") EditorSection("Certificate expiry", "Warn within $warningDays days of expiry", Icons.Outlined.VerifiedUser, collapsible = true, enabled = !busy) {
                OutlinedTextField(warningDays, { warningDays = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Expiry warning (days)") }, supportingText = { Text("Between 0 and 365 days") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            EditorSection("Alerts & maintenance", "$failures ${if (failures == "1") "failure" else "failures"} to mark down · ${if (maintenance > System.currentTimeMillis()) "Maintenance on" else "Maintenance off"}", Icons.Outlined.Notifications, collapsible = true, enabled = !busy) {
                Text("State changes", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(failures, { failures = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Failures before DOWN") }, supportingText = { Text("Consecutive failed checks, from 1 to 20") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(recoveries, { recoveries = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Successes before UP") }, supportingText = { Text("Consecutive successful checks, from 1 to 20") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                EditorToggle("Alert when first observed state is down", "Notify even if the service has never been observed up.", initialDown, !busy) { initialDown = it }
                OutlinedTextField(repeat, { repeat = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Repeat down alert (minutes)") }, supportingText = { Text("Set to 0 to turn reminders off") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                HorizontalDivider()
                EditorDropdown("Maintenance", if (maintenance > System.currentTimeMillis()) "Until ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(maintenance))}" else "Off", listOf("Off", "1 hour", "4 hours", "24 hours"), !busy) {
                    maintenance = when (it) { "1 hour" -> 1L; "4 hours" -> 4L; "24 hours" -> 24L; else -> 0L }.let { h -> if (h == 0L) 0 else System.currentTimeMillis() + h * 3_600_000 }
                }
                Text("Maintenance skips checks and pauses endpoint alerts.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            EditorSection("Organization", if (tags.isBlank()) "Add optional tags" else tags, Icons.AutoMirrored.Outlined.Label, collapsible = true, enabled = !busy) {
                OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Tags (comma separated)") }, placeholder = { Text("production, api") })
            }
        }
        Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val message = actionError ?: validation?.takeIf { name.isNotBlank() && target.isNotBlank() }
                if (message != null) Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                else Text(if (name.isBlank() || target.isBlank()) "Add a name and target to enable Save." else "Your target is checked before saving.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppSecondaryButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    AppButton(enabled = validation == null && !busy, modifier = Modifier.weight(2f), onClick = {
                        busy = true
                        actionError = null
                        scope.launch {
                            var saving = false
                            try {
                                val service = prepared()
                                val result = withContext(Dispatchers.IO) { probe?.execute(service) ?: ProbeEngine.runner(context).run(service) }
                                if (result.up == true) { saving = true; onSave(service) }
                                else actionError = "Check failed: ${result.detail}"
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { actionError = if (saving) "Could not save the monitor. Please try again." else "Could not test the monitor. Please try again." }
                            finally { busy = false }
                        }
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (busy) CircularProgressIndicator(Modifier.size(16.dp).testTag("save-progress"), color = LocalContentColor.current, strokeWidth = 2.dp)
                            Text(if (busy) "Checking…" else "Save")
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun SwitchRow(label: String, checked: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(checked, changed) }
}

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
import androidx.compose.ui.text.style.TextOverflow
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

@Composable internal fun ServiceEditor(existing: ServiceEntry?, modifier: Modifier = Modifier, onCancel: () -> Unit, probe: Probe? = null, headerSecrets: HeaderSecrets? = null, onSave: suspend (ServiceEntry) -> Unit) {
    val context = LocalContext.current
    val editId = existing?.id
    val defaults = remember { AppSettings.read(AppSettings.prefs(context)) }
    val scope = rememberCoroutineScope()
    val secrets = remember(headerSecrets) { headerSecrets ?: SecretStore() }
    val loadedHeaders = remember(editId) { runCatching { secrets.decrypt(existing?.headersCiphertext ?: "") } }
    val authorization = loadedHeaders.getOrDefault(emptyMap()).entries.firstOrNull { it.key.equals("Authorization", true) }?.value.orEmpty()
    var clearBrokenCredentials by remember { mutableStateOf(false) }
    var name by rememberSaveable(editId) { mutableStateOf(existing?.name ?: "") }
    var protocol by rememberSaveable(editId) { mutableStateOf(existing?.url?.substringBefore("://") ?: "https") }
    var target by rememberSaveable(editId) { mutableStateOf(existing?.url?.substringAfter("://") ?: "") }
    var interval by rememberSaveable(editId) { mutableIntStateOf((existing?.intervalSeconds ?: defaults.intervalSeconds).coerceAtLeast(300)) }
    var tags by rememberSaveable(editId) { mutableStateOf(existing?.tags ?: "") }
    var method by rememberSaveable(editId) { mutableStateOf(existing?.method ?: "GET") }
    var anySuccess by rememberSaveable(editId) { mutableStateOf(existing?.expectedCode?.let { it == 0 } ?: true) }
    var status by rememberSaveable(editId) { mutableStateOf(existing?.expectedCode?.takeIf { it != 0 }?.toString() ?: "200") }
    var bodyType by rememberSaveable(editId) { mutableStateOf(existing?.bodyType ?: "None") }
    var expectedBody by remember(editId) { mutableStateOf(existing?.expectedBody ?: "") }
    var jsonPath by rememberSaveable(editId) { mutableStateOf(existing?.jsonPath ?: "") }
    var requestBody by remember(editId) { mutableStateOf(existing?.requestBody ?: "") }
    val knownAuth = authorization.startsWith("Bearer ") || authorization.startsWith("Basic ")
    var headerRows by remember(editId) { mutableStateOf(loadedHeaders.getOrDefault(emptyMap()).filterKeys { !knownAuth || !it.equals("Authorization", true) }.map { HeaderDraft(it.key, it.value) }) }
    var xmlStructural by rememberSaveable(editId) { mutableStateOf(existing?.xmlStructural ?: true) }
    var xmlIgnoreWhitespace by rememberSaveable(editId) { mutableStateOf(existing?.xmlIgnoreWhitespace ?: false) }
    var requestLanguage by rememberSaveable(editId) { mutableStateOf("Text") }
    var authMode by remember(editId) { mutableStateOf(if (authorization.startsWith("Bearer ")) "Bearer" else if (authorization.startsWith("Basic ")) "Basic" else "None") }
    val basic = remember(authorization) { runCatching { String(Base64.decode(authorization.removePrefix("Basic "), Base64.DEFAULT), Charsets.ISO_8859_1) }.getOrDefault("") }
    var username by remember(editId) { mutableStateOf(if (authMode == "Basic") basic.substringBefore(':') else "") }
    var password by remember(editId) { mutableStateOf(if (authMode == "Basic") basic.substringAfter(':', "") else "") }
    var bearer by remember(editId) { mutableStateOf(if (authMode == "Bearer") authorization.removePrefix("Bearer ") else "") }
    var timeout by rememberSaveable(editId) { mutableStateOf((existing?.timeoutSeconds ?: defaults.timeoutSeconds).toString()) }
    var redirects by rememberSaveable(editId) { mutableStateOf(existing?.followRedirects ?: true) }
    var validated by rememberSaveable(editId) { mutableStateOf(existing?.requireValidatedInternet ?: defaults.requireInternet) }
    var failures by rememberSaveable(editId) { mutableIntStateOf(existing?.failureThreshold ?: defaults.failures) }
    var recoveries by rememberSaveable(editId) { mutableIntStateOf(existing?.recoveryThreshold ?: defaults.recoveries) }
    var reviewed by rememberSaveable(editId) { mutableStateOf(existing?.needsReview != true) }
    var initialDown by rememberSaveable(editId) { mutableStateOf(existing?.notifyInitialDown ?: true) }
    var repeat by rememberSaveable(editId) { mutableIntStateOf(AlertReminders.normalize(existing?.repeatMinutes ?: AlertReminders.DEFAULT_MINUTES)) }
    val maintenance = existing?.maintenanceUntil ?: 0
    var warningDays by rememberSaveable(editId) { mutableStateOf((existing?.certificateWarnDays ?: 30).toString()) }
    var dnsExpected by rememberSaveable(editId) { mutableStateOf(existing?.dnsExpected ?: "") }
    var busy by remember { mutableStateOf(false) }
    var probeFailed by remember { mutableStateOf(false) }
    var savingPaused by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val headerCount = headerRows.count { it.name.isNotBlank() }
    val url = "$protocol://${target.trim()}"
    val http = protocol in listOf("http", "https")
    val parsedHeaders = remember(headerRows, authMode, bearer, username, password) { runCatching {
        val values = headerValues(headerRows).toMutableMap()
        require(authMode == "None" || values.keys.none { it.equals("Authorization", true) }) { "Use authentication or an Authorization header, not both." }
        when (authMode) {
            "Bearer" -> values["Authorization"] = "Bearer ${bearer.trim()}"
            "Basic" -> values["Authorization"] = Credentials.basic(username, password)
        }
        values.toMap()
    } }
    val draft = ServiceEntry(id = editId ?: 0, name = name.trim(), url = url, intervalSeconds = interval, enabled = existing?.enabled ?: true, expectedCode = if (anySuccess) 0 else status.toIntOrNull() ?: -1, bodyType = if (http) bodyType else "None", expectedBody = expectedBody, method = if (http) method else "GET", requestBody = requestBody, jsonPath = jsonPath, timeoutSeconds = timeout.toIntOrNull() ?: 0, followRedirects = redirects, requireValidatedInternet = validated, failureThreshold = failures, recoveryThreshold = recoveries, notifyInitialDown = initialDown, repeatMinutes = repeat, maintenanceUntil = maintenance, certificateWarnDays = warningDays.toIntOrNull() ?: -1, dnsExpected = dnsExpected, tags = tags.trim(), xmlStructural = xmlStructural, xmlIgnoreWhitespace = xmlIgnoreWhitespace)
    var bodyValidation by remember(bodyType, expectedBody, jsonPath, http) { mutableStateOf<String?>(if (!http || bodyType == "None") null else "Checking response…") }
    LaunchedEffect(bodyType, expectedBody, jsonPath, http) {
        if (http && bodyType != "None") {
            kotlinx.coroutines.delay(150)
            bodyValidation = withContext(Dispatchers.Default) { BodyMatcher.validate(bodyType, expectedBody, jsonPath) }
        }
    }
    val validation = when {
        !reviewed -> "Review the imported settings before saving."
        loadedHeaders.isFailure && !clearBrokenCredentials -> "Saved credentials can't be opened. Clear them and enter them again."
        parsedHeaders.isFailure -> parsedHeaders.exceptionOrNull()?.message ?: "Invalid headers"
        else -> remember(draft, parsedHeaders.getOrNull()) { ServiceValidation.error(draft, parsedHeaders.getOrDefault(emptyMap()), allowIncompleteBody = true) } ?: bodyValidation
    }
    val prepared: suspend () -> ServiceEntry = {
        withContext(Dispatchers.IO) {
            val headers = parsedHeaders.getOrThrow().toMutableMap()
            require(ServiceValidation.error(draft, headers) == null) { "Check the response rules." }
            val parsedUrl = url.toHttpUrlOrNull()
            if (parsedUrl != null && parsedUrl.username.isNotBlank() && headers.keys.none { it.equals("Authorization", true) }) headers["Authorization"] = Credentials.basic(parsedUrl.username, parsedUrl.password)
            draft.copy(url = parsedUrl?.newBuilder()?.username("")?.password("")?.build()?.toString() ?: url, headersCiphertext = secrets.encrypt(headers))
        }
    }
    val scroll = rememberScrollState()
    Column(modifier.fillMaxSize().imePadding()) {
        AdaptiveSections(
            Modifier.weight(1f).verticalScroll(scroll).padding(16.dp),
            spacing = 16.dp, pairedRows = true
        ) {
            EditorSection("Service details", "Name and address", Icons.Outlined.Dns) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Name") }, placeholder = { Text("e.g. Production API") }, singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    ProtocolPicker(protocol, enabled = !busy) { protocol = it }
                    OutlinedTextField(target, { input ->
                        val value = input.trim()
                        val pasted = value.substringBefore("://").lowercase(java.util.Locale.ROOT)
                        if (value.contains("://") && pasted in listOf("http", "https", "tcp", "ping", "dns", "tls")) {
                            protocol = pasted
                            target = value.substringAfter("://")
                        } else target = input
                    }, Modifier.weight(1f).heightIn(min = 64.dp).testTag("monitor-target"), enabled = !busy, label = { Text(if (protocol == "tcp") "Host:port" else "Address") }, placeholder = { Text(if (protocol == "tcp") "example.com:443" else "example.com") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                }
                Text(when (protocol) {
                    "tcp" -> "Checks whether a connection can be opened to this port."
                    "ping" -> "Checks host reachability. Some hosts and networks block ping."
                    "dns" -> "Resolves this host and optionally verifies its IP addresses."
                    "tls" -> "Checks certificate trust, hostname and expiry."
                    else -> "Checks the HTTP response from this address."
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (existing?.needsReview == true) EditorSection("Review imported settings", "This template left out private values.", Icons.AutoMirrored.Outlined.FactCheck) {
                EditorToggle("I've reviewed this monitor", "Check credentials, URL query values, and response rules before starting it.", reviewed, !busy) { reviewed = it }
            }
            EditorSection("Monitoring", "Schedule and network", Icons.Outlined.Schedule) {
                IntervalSlider(interval, existing?.intervalSeconds, enabled = !busy) { interval = it }
                HorizontalDivider()
                EditorToggle("Require Internet", "Skip checks without Internet access. Turn off for local services.", validated, !busy) { validated = it }
            }
            EditorSection("Request settings", if (http) "$method · ${timeout}s timeout" else "${timeout}s timeout", Icons.Outlined.Tune, collapsible = true, enabled = !busy) {
                if (http) EditorDropdown("HTTP method", method, listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"), !busy) { method = it; if (it == "HEAD") bodyType = "None" }
                OutlinedTextField(timeout, { timeout = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Timeout (seconds)") }, supportingText = { Text("1–60 seconds") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (http) {
                    EditorToggle("Follow redirects", "Follow the link when a server redirects.", redirects, !busy) { redirects = it }
                    if (method in listOf("POST", "PUT", "PATCH")) {
                        EditorDropdown("Body language", requestLanguage, listOf("Text", "JSON", "YAML", "XML"), !busy) { requestLanguage = it }
                        BodyEditor("Request body", requestBody, requestLanguage, !busy, onChange = { requestBody = it })
                        Text("Language controls editing. Set Content-Type in custom headers if needed.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (http) {
                EditorSection("Response rules", "${if (anySuccess) "Any 2xx status" else "HTTP $status"} · ${if (bodyType == "None") "No body match" else "$bodyType match"}", Icons.AutoMirrored.Outlined.FactCheck, collapsible = true, enabled = !busy) {
                    EditorToggle("Accept any 2xx status", "Accept any successful response.", anySuccess, !busy) { anySuccess = it }
                    if (!anySuccess) OutlinedTextField(status, { status = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Expected HTTP status") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    if (method != "HEAD") EditorDropdown("Response body match", bodyType, listOf("None", "Text", "JSON", "YAML", "XML"), !busy) { bodyType = it }
                    if (bodyType != "None") {
                        BodyEditor("Expected response", expectedBody, bodyType, !busy, problem = bodyValidation, onChange = { expectedBody = it })
                        Text(when (bodyType) {
                            "JSON", "YAML" -> "Ignores formatting and key order. Array order and value types still matter."
                            "XML" -> "Choose how XML structure and whitespace are compared."
                            else -> "Matches the response text exactly, including whitespace."
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (bodyType == "XML") {
                        EditorToggle("Compare XML structure", "Ignore namespace prefixes, attribute order and comments.", xmlStructural, !busy) { xmlStructural = it }
                        if (xmlStructural) EditorToggle("Ignore whitespace-only text", "Ignore indentation. Text marked xml:space=preserve stays significant.", xmlIgnoreWhitespace, !busy) { xmlIgnoreWhitespace = it }
                    }
                    if (bodyType == "JSON") OutlinedTextField(jsonPath, { jsonPath = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("JSON path (optional, e.g. $.status)") }, supportingText = { Text("Use valid JSON for the expected value, such as \"ok\". A blank path compares the full document.") })
                }
                EditorSection("Authentication & headers", if (loadedHeaders.isFailure && !clearBrokenCredentials) "Saved credentials need attention" else if (authMode == "None" && headerRows.isEmpty()) "No authentication or custom headers" else "${if (authMode == "None") "No authentication" else "$authMode authentication"} · $headerCount ${if (headerCount == 1) "header" else "headers"}", Icons.Outlined.Lock, collapsible = true, initiallyExpanded = loadedHeaders.isFailure, enabled = !busy) {
                    EditorDropdown("Authentication", authMode, listOf("None", "Bearer", "Basic"), !busy) { authMode = it }
                    if (authMode == "Bearer") OutlinedTextField(bearer, { bearer = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Bearer token") }, visualTransformation = PasswordVisualTransformation())
                    if (authMode == "Basic") {
                        OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Username") })
                        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation())
                    }
                    HeaderEditor(headerRows, !busy) { headerRows = it }
                    Text("Credentials are encrypted on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (loadedHeaders.isFailure) EditorToggle("Replace saved credentials", "Enter credentials this device can open.", clearBrokenCredentials, !busy) { clearBrokenCredentials = it }
                }
            }
            if (protocol == "dns") EditorSection("DNS answers", if (dnsExpected.isBlank()) "Accept any resolved IP address" else "Check expected IP addresses", Icons.AutoMirrored.Outlined.FactCheck, collapsible = true, enabled = !busy) {
                OutlinedTextField(dnsExpected, { dnsExpected = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Expected IP addresses") }, supportingText = { Text("Optional. Separate IPv4 or IPv6 addresses with commas.") })
            }
            if (protocol == "tls") EditorSection("Certificate expiry", "Warn within $warningDays days of expiry", Icons.Outlined.VerifiedUser, collapsible = true, enabled = !busy) {
                OutlinedTextField(warningDays, { warningDays = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Expiry warning (days)") }, supportingText = { Text("Between 0 and 365 days") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            EditorSection("Alerts", "$failures ${if (failures == 1) "failure" else "failures"} to mark down · ${if (repeat == 0) "Reminders off" else "Repeat every ${formatReminderInterval(repeat)}"}", Icons.Outlined.Notifications, collapsible = true, enabled = !busy) {
                Text("State changes", style = MaterialTheme.typography.titleSmall)
                ConfirmationSlider(failures, recovery = false, enabled = !busy) { failures = it }
                ConfirmationSlider(recoveries, recovery = true, enabled = !busy) { recoveries = it }
                EditorToggle("Alert on the first outage", "Notify even if this monitor hasn't been up yet.", initialDown, !busy) { initialDown = it }
                RepeatAlertSlider(repeat, existing?.repeatMinutes, enabled = !busy) { repeat = it }

            }
            EditorSection("Organization", if (tags.isBlank()) "Add optional tags" else tags, Icons.AutoMirrored.Outlined.Label, collapsible = true, enabled = !busy) {
                OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Tags (comma separated)") }, placeholder = { Text("production, api") })
            }
        }
        Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val message = actionError ?: validation?.takeIf { name.isNotBlank() && target.isNotBlank() }
                if (message != null) Text(message, color = if (message == "Checking response…") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                else if (name.isNotBlank() && target.isNotBlank()) Text("We'll check the connection when you save.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (probeFailed) {
                    Text("Save it paused and start monitoring when you're ready.", style = MaterialTheme.typography.bodySmall)
                    AppSecondaryButton(enabled = validation == null && !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                        busy = true
                        savingPaused = true
                        scope.launch {
                            try { onSave(prepared().copy(enabled = false)) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { actionError = "Couldn't save this monitor. Try again." }
                            finally { busy = false; savingPaused = false }
                        }
                    }) { Text("Save paused") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppSecondaryButton(onClick = onCancel, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { Text("Cancel", maxLines = 1) }
                    AppButton(enabled = validation == null && !busy, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), onClick = {
                        busy = true
                        probeFailed = false
                        actionError = null
                        scope.launch {
                            var saving = false
                            try {
                                val service = prepared()
                                val result = withContext(Dispatchers.IO) { probe?.execute(service) ?: ProbeEngine.runner(context).run(service) }
                                if (result.up == true) { saving = true; onSave(service) }
                                else { probeFailed = true; actionError = "Check failed: ${result.detail}" }
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { probeFailed = !saving; actionError = if (saving) "Couldn't save this monitor. Try again." else "Couldn't check the connection. Try again." }
                            finally { busy = false }
                        }
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (busy) CircularProgressIndicator(Modifier.size(16.dp).testTag("save-progress"), color = LocalContentColor.current, strokeWidth = 2.dp)
                            Text(if (savingPaused) "Saving…" else if (busy) "Checking…" else "Save", Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
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

package com.pocketupwatch

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.langs.textmate.registry.provider.FileResolver
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.tm4e.core.registry.IThemeSource

/** A single Content owns the undo history while the native view moves into the expanded editor. */
@Composable internal fun BodyEditor(label: String, value: String, language: String, enabled: Boolean = true, readOnly: Boolean = false, problem: String? = null, onChange: (String) -> Unit = {}) {
    val clipboard = LocalClipboardManager.current
    val content = remember { Content(value) }
    var revision by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var wrap by remember { mutableStateOf(true) }
    var options by remember { mutableStateOf(false) }
    var result by remember(language) { mutableStateOf<String?>(null) }
    var resultError by remember { mutableStateOf(false) }
    var formatted by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val latestChange by rememberUpdatedState(onChange)
    val latestValue by rememberUpdatedState(value)
    val changed = { revision++; latestChange(content.toString()); result = null }
    LaunchedEffect(value) { if (content.toString() != latestValue) content.replace(0, content.length, latestValue) }
    val structured = language in listOf("JSON", "YAML", "XML")
    val bytes = remember(value) { value.toByteArray(Charsets.UTF_8).size }
    val size = if (bytes < 1024) "$bytes B" else "${(bytes / 1024.0 * 10).toInt() / 10.0} KiB"
    @Composable fun heading(full: Boolean) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f), style = if (full) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall)
            Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(language, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
            }
            if (full) TextButton(onClick = { expanded = false }) { Text("Done") }
            Box {
                IconButton(onClick = { options = true }) { Icon(Icons.Outlined.MoreVert, "Editor options") }
                DropdownMenu(options, { options = false }) {
                    DropdownMenuItem(text = { Text("Wrap lines") }, leadingIcon = { Icon(if (wrap) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null) }, onClick = { wrap = !wrap; options = false })
                    DropdownMenuItem(text = { Text("Copy all") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = { clipboard.setText(AnnotatedString(value)); options = false })
                }
            }
        }
    }
    @Composable fun tools() {
        BoxWithConstraints {
            val compact = maxWidth < 320.dp || LocalDensity.current.fontScale > 1.3f
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (!readOnly) {
                    IconButton(enabled = enabled && !working && revision >= 0 && content.canUndo(), onClick = { content.undo() }) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo") }
                    IconButton(enabled = enabled && !working && content.canRedo(), onClick = { content.redo() }) { Icon(Icons.AutoMirrored.Outlined.Redo, "Redo") }
                    if (structured) TextButton(enabled = enabled && !working, onClick = {
                        working = true
                        val source = content.toString()
                        scope.launch {
                            try {
                                val preview = withContext(Dispatchers.Default) { runCatching { StructuredBodies.format(language, source) } }
                                if (content.toString() == source) {
                                    formatted = preview.getOrNull()
                                    result = preview.exceptionOrNull()?.let { StructuredBodies.problem(language, source)?.description ?: it.message ?: "Couldn't format this value." }
                                    resultError = preview.isFailure
                                }
                            } finally { working = false }
                        }
                    }) { Icon(Icons.Outlined.AutoFixHigh, if (compact) "Format" else null, Modifier.size(18.dp)); if (!compact) { Spacer(Modifier.width(6.dp)); Text("Format") } }
                } else TextButton(onClick = { clipboard.setText(AnnotatedString(value)) }) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copy") }
                Spacer(Modifier.weight(1f))
                if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                if (!expanded) TextButton(enabled = !working, onClick = { expanded = true }) { Icon(Icons.Outlined.OpenInFull, if (compact) "Expand" else null, Modifier.size(18.dp)); if (!compact) { Spacer(Modifier.width(6.dp)); Text("Expand") } }
            }
        }
    }
    @Composable fun footer() {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("${content.lineCount} ${if (content.lineCount == 1) "line" else "lines"} · $size", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (structured && !readOnly) TextButton(enabled = enabled && !working, onClick = {
                working = true
                val source = content.toString()
                scope.launch {
                    try {
                        val issue = withContext(Dispatchers.Default) { StructuredBodies.problem(language, source) }
                        if (content.toString() == source) { result = issue?.description ?: "Valid $language"; resultError = issue != null }
                    } finally { working = false }
                }
            }) { Text("Validate", style = MaterialTheme.typography.labelMedium) }
        }
        val message = problem?.takeUnless { it == "Checking response…" } ?: result
        if (message != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val error = problem?.takeUnless { it == "Checking response…" } != null || resultError
            Icon(if (error) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle, null, Modifier.size(18.dp), tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text(message, style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (bytes > 131_072) Text("Highlighting is off for large bodies.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
    Column(Modifier.testTag("body-editor-$label"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!expanded) {
            heading(false)
            Surface(shape = MaterialTheme.shapes.medium, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    tools()
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NativeBodyEditor(content, language, enabled && !readOnly, Modifier.fillMaxWidth().height(((content.lineCount + 2) * 22).coerceIn(176, 286).dp).testTag(label), wrap, changed)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    footer()
                }
            }
        }
    }
    if (expanded) Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().imePadding(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                heading(true)
                tools()
                HorizontalDivider()
                NativeBodyEditor(content, language, enabled && !readOnly, Modifier.fillMaxWidth().weight(1f).testTag("expanded-$label"), wrap, changed)
                footer()
            }
        }
    }
    formatted?.let { preview ->
        Dialog(onDismissRequest = { formatted = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.widthIn(max = 900.dp).fillMaxWidth().fillMaxHeight(.9f).padding(16.dp), shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Format preview", style = MaterialTheme.typography.titleLarge)
                    Text(if (language == "XML") "Formatting adds whitespace. Review it before applying, especially when matching whitespace." else if (language == "YAML") "Formatting removes comments and expands YAML values. Your saved text changes only when you apply." else "Review the formatted value before applying.", style = MaterialTheme.typography.bodySmall)
                    NativeBodyEditor(remember(preview) { Content(preview) }, language, false, Modifier.fillMaxWidth().weight(1f), wrap) {}
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { formatted = null }) { Text("Cancel") }
                        AppButton(onClick = { content.replace(0, content.length, preview); formatted = null }) { Text("Apply") }
                    }
                }
            }
        }
    }
}

private object EditorAssets {
    private var assets: android.content.res.AssetManager? = null
    private var resolver: FileResolver? = null
    @Synchronized fun register(context: Context) {
        if (assets !== context.applicationContext.assets) {
            val providers = FileProviderRegistry.getInstance()
            resolver?.let { providers.removeFileProvider(it) }
            assets = context.applicationContext.assets
            resolver = AssetsFileResolver(assets!!)
            providers.addFileProvider(resolver!!)
        }
    }
}

@Composable private fun NativeBodyEditor(content: Content, language: String, editable: Boolean, modifier: Modifier, wrap: Boolean, onChange: () -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val latestChange by rememberUpdatedState(onChange)
    val highlighted = language in listOf("JSON", "YAML", "XML") && content.length <= 131_072 && content.toString().toByteArray(Charsets.UTF_8).size <= 131_072
    key(language, dark, highlighted) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                EditorAssets.register(context)
                PrivateCodeEditor(context).apply {
                    contentDescription = "${if (editable) "Edit" else "Read"} $language body"
                    setTextSize(14f)
                    setTabWidth(2)
                    setLineNumberMarginLeft(6f)
                    setDividerMargin(6f)
                    setLineNumberEnabled(true)
                    setWordwrap(wrap)
                    setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
                    val themes = ThemeRegistry()
                    val themeFile = "textmate/${if (dark) "dark" else "light"}.json"
                    val theme = ThemeModel(IThemeSource.fromInputStream(context.assets.open(themeFile), themeFile, Charsets.UTF_8)).apply { setDark(dark) }
                    themes.loadTheme(theme)
                    colorScheme = TextMateColorScheme.create(themes)
                    if (highlighted) {
                        val grammars = GrammarRegistry(null)
                        grammars.setTheme(theme)
                        grammars.loadGrammars("textmate/languages.json")
                        val scope = when (language) { "XML" -> "text.xml"; "YAML" -> "source.yaml"; else -> "source.json" }
                        setEditorLanguage(TextMateLanguage.create(scope, grammars, themes, false).apply { isAutoCompleteEnabled = false })
                    } else setEditorLanguage(EmptyLanguage())
                    val cursor = content.cursor
                    val leftLine = cursor.leftLine; val leftColumn = cursor.leftColumn
                    val rightLine = cursor.rightLine; val rightColumn = cursor.rightColumn
                    setText(content, true, null)
                    setSelectionRegion(leftLine, leftColumn, rightLine, rightColumn)
                    isEditable = editable
                    subscribeEvent(ContentChangeEvent::class.java) { _, _ -> latestChange() }
                }
            },
            update = { it.isEditable = editable; if (it.isWordwrap != wrap) it.setWordwrap(wrap) },
            onRelease = { it.release() }
        )
    }
}

/** Request and expected bodies may contain secrets; ask IMEs not to learn from them. */
private class PrivateCodeEditor(context: Context) : CodeEditor(context) {
    override fun onCreateInputConnection(outAttrs: EditorInfo): android.view.inputmethod.InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs)
        outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return connection
    }
}

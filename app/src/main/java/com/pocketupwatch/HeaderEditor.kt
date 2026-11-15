package com.pocketupwatch

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.util.UUID

internal data class HeaderDraft(val name: String = "", val value: String = "", val id: String = UUID.randomUUID().toString())
internal fun headerValues(rows: List<HeaderDraft>): Map<String, String> {
    val values = linkedMapOf<String, String>()
    rows.filter { it.name.isNotBlank() || it.value.isNotBlank() }.forEach { row ->
        val name = row.name.trim()
        require(name.isNotEmpty()) { "Give each header a name." }
        require(values.keys.none { it.equals(name, true) }) { "Remove the duplicate $name header." }
        values[name] = row.value.trim()
    }
    return values
}

@Composable internal fun HeaderEditor(rows: List<HeaderDraft>, enabled: Boolean, onChange: (List<HeaderDraft>) -> Unit) {
    Text("Custom headers", style = MaterialTheme.typography.titleSmall)
    if (rows.isEmpty()) Text("No custom headers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    rows.forEach { row -> key(row.id) {
        var revealed by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().testTag("header-${row.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(row.name, { name -> onChange(rows.map { if (it.id == row.id) it.copy(name = name) else it }) }, Modifier.weight(1f), enabled = enabled, singleLine = true, label = { Text("Header name") }, placeholder = { Text("Content-Type") }, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                IconButton(enabled = enabled, onClick = { onChange(rows.filterNot { it.id == row.id }) }) { Icon(Icons.Outlined.Delete, "Delete header ${row.name.ifBlank { "row" }}") }
            }
            OutlinedTextField(row.value, { value -> onChange(rows.map { if (it.id == row.id) it.copy(value = value) else it }) }, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true, label = { Text("Value") }, visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), trailingIcon = {
                IconButton(onClick = { revealed = !revealed }) { Icon(if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (revealed) "Hide header value" else "Show header value") }
            })
        }
    } }
    TextButton(enabled = enabled, onClick = { onChange(rows + HeaderDraft()) }) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Add header") }
}

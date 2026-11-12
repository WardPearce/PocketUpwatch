package com.pocketupwatch

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(title = { Text(title) }, navigationIcon = {
        if (onBack != null) {
            // TopAppBar adds 4dp before its title; the extra 12dp gives a 16dp gap.
            AppIconButton(onBack, Modifier.padding(start = 8.dp, end = 12.dp).testTag("app-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        }
    }, actions = actions)
}

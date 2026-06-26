package com.usesense.sdk.ui.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.usesense.sdk.ui.compose.UseSenseTheme

/**
 * Standard immersive screen container the rebuilt capture screens reuse: brand
 * background, system-bar insets, optional branding header and pinned footer.
 */
@Composable
fun USScreenScaffold(
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = UseSenseTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .systemBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        if (header != null) {
            Spacer(Modifier.height(8.dp))
            header()
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.weight(1f), content = content)
        if (footer != null) {
            Spacer(Modifier.height(12.dp))
            footer()
            Spacer(Modifier.height(8.dp))
        }
    }
}

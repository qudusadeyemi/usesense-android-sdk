package com.usesense.sdk.ui.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.usesense.sdk.ui.compose.UseSenseTheme

/** Soft elevated surface matching the hosted page cards. */
@Composable
fun USCard(
    modifier: Modifier = Modifier,
    padding: Dp = 20.dp,
    radius: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = UseSenseTheme.colors
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(elevation = 12.dp, shape = shape, clip = false)
            .clip(shape)
            .background(colors.card)
            .padding(padding),
        content = content,
    )
}

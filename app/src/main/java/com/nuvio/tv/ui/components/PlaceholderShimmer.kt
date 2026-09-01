package com.nuvio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color

private val STATIC_PLACEHOLDER_COLOR = Color.White.copy(alpha = 0.04f)

@Composable
fun rememberPlaceholderShimmerOffsetState(label: String): State<Float> {
    return remember { mutableFloatStateOf(0f) }
}

fun Modifier.placeholderCardShimmer(
    shimmerOffsetState: State<Float>,
    backgroundColor: Color? = null
): Modifier = drawWithCache {
    onDrawBehind {
        backgroundColor?.let { drawRect(color = it) }
        drawRect(color = STATIC_PLACEHOLDER_COLOR)
    }
}

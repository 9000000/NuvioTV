package com.nuvio.tv.ui.util

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

val LocalRecompositionHighlighterEnabled = compositionLocalOf { false }

/**
 * Zero-overhead no-op in production.
 */
@Stable
fun Modifier.recompositionHighlighter(): Modifier = this

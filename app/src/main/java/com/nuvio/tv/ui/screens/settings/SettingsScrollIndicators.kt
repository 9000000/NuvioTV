@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon

private val IndicatorIconSize = NuvioTheme.spacing.xxl
private val IndicatorEdgeInset = NuvioTheme.spacing.xxs

@Composable
internal fun BoxScope.SettingsVerticalScrollIndicators(
    state: ScrollableState,
    modifier: Modifier = Modifier
) {
    if (state.canScrollBackward) {
        Icon(
            imageVector = Icons.Default.KeyboardArrowUp,
            contentDescription = null,
            tint = NuvioTheme.colors.TextSecondary.copy(alpha = 0.55f),
            modifier = modifier
                .align(Alignment.TopCenter)
                .padding(top = IndicatorEdgeInset)
                .size(IndicatorIconSize)
        )
    }
    if (state.canScrollForward) {
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = NuvioTheme.colors.TextSecondary.copy(alpha = 0.55f),
            modifier = modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = IndicatorEdgeInset)
                .size(IndicatorIconSize)
        )
    }
}

@Composable
internal fun BoxScope.SettingsHorizontalScrollIndicators(
    state: ScrollableState,
    modifier: Modifier = Modifier
) {
    if (state.canScrollBackward) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = null,
            tint = NuvioTheme.colors.TextSecondary.copy(alpha = 0.55f),
            modifier = modifier
                .align(Alignment.CenterStart)
                .padding(start = IndicatorEdgeInset)
                .size(IndicatorIconSize)
        )
    }
    if (state.canScrollForward) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = NuvioTheme.colors.TextSecondary.copy(alpha = 0.55f),
            modifier = modifier
                .align(Alignment.CenterEnd)
                .padding(end = IndicatorEdgeInset)
                .size(IndicatorIconSize)
        )
    }
}

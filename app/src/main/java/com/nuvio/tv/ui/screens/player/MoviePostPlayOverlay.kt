@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.annotation.RawRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.formatHeroRuntime
import com.nuvio.tv.ui.util.localizedGenreLabel

@Composable
fun MoviePostPlayOverlay(
    state: MoviePostPlayUiState,
    currentTitle: String,
    onPlay: (MoviePostPlayRecommendation) -> Unit,
    onPlayTrailer: () -> Unit,
    onTrailerEnded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val recommendation = state.recommendation ?: return
    val playFocusRequester = remember(recommendation.id) { FocusRequester() }
    val trailerFocusRequester = remember(recommendation.id) { FocusRequester() }
    val context = LocalContext.current
    val imageLoader = context.imageLoader
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val playPainter = rememberMoviePostPlayIcon(R.raw.ic_player_play)
    val trailerPainter = rememberMoviePostPlayIcon(R.raw.trailer_play_button)
    var logoLoadFailed by remember(recommendation.logo) { mutableStateOf(false) }
    val showLogo = !recommendation.logo.isNullOrBlank() && !logoLoadFailed
    val horizontalScrim = if (isRtl) {
        Brush.horizontalGradient(
            0f to Color.Black.copy(alpha = 0.22f),
            0.46f to Color.Black.copy(alpha = 0.16f),
            1f to Color.Black.copy(alpha = 0.88f)
        )
    } else {
        Brush.horizontalGradient(
            0f to Color.Black.copy(alpha = 0.88f),
            0.54f to Color.Black.copy(alpha = 0.16f),
            1f to Color.Black.copy(alpha = 0.22f)
        )
    }

    LaunchedEffect(recommendation.id, state.isVisible) {
        if (!state.isVisible) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        runCatching { playFocusRequester.requestFocus() }
    }

    LaunchedEffect(recommendation.id, recommendation.backdrop, recommendation.logo) {
        recommendation.backdrop?.let { url ->
            imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .size(1280, 720)
                    .build()
            )
        }
        recommendation.logo?.let { url ->
            imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .size(512, 192)
                    .build()
            )
        }
    }

    AnimatedVisibility(
        visible = state.isVisible,
        enter = fadeIn(animationSpec = tween(360)),
        exit = fadeOut(animationSpec = tween(220)),
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = recommendation.backdrop ?: recommendation.poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            )

            TrailerPlayer(
                trailerUrl = recommendation.trailerVideoUrl,
                trailerAudioUrl = recommendation.trailerAudioUrl,
                isPlaying = state.isTrailerPlaying,
                onEnded = onTrailerEnded,
                cropToFill = true,
                modifier = Modifier.fillMaxSize()
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(horizontalScrim)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.08f),
                            0.58f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.82f)
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(0.52f)
                    .padding(
                        start = NuvioTheme.spacing.screen.overscanHorizontal,
                        bottom = NuvioTheme.spacing.screen.overscanVertical
                    ),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = if (currentTitle.isBlank()) {
                        stringResource(R.string.player_post_play_recommended)
                    } else {
                        stringResource(R.string.player_post_play_because, currentTitle)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.extendedColors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

                if (showLogo) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(recommendation.logo)
                            .crossfade(true)
                            .build(),
                        contentDescription = recommendation.title,
                        onError = { logoLoadFailed = true },
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                        modifier = Modifier
                            .fillMaxWidth(0.76f)
                            .heightIn(max = 92.dp)
                    )
                } else {
                    Text(
                        text = recommendation.title,
                        style = MaterialTheme.typography.displayMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val metadata = recommendation.metadataLine(context)
                val tmdbRating = recommendation.tmdbRating?.takeIf { it > 0f }
                if (metadata.isNotBlank() || tmdbRating != null) {
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (metadata.isNotBlank()) {
                            Text(
                                text = metadata,
                                style = MaterialTheme.typography.labelLarge,
                                color = NuvioTheme.extendedColors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        if (metadata.isNotBlank() && tmdbRating != null) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.labelLarge,
                                color = NuvioTheme.extendedColors.textTertiary
                            )
                        }
                        if (tmdbRating != null) {
                            AsyncImage(
                                model = R.raw.mdblist_tmdb,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(NuvioTheme.spacing.xl)
                            )
                            Text(
                                text = (tmdbRating * 10).toInt().toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = NuvioTheme.extendedColors.textSecondary
                            )
                        }
                    }
                }

                if (!recommendation.description.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                    Text(
                        text = recommendation.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(0.92f)
                    )
                }

                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xl))

                Column(
                    modifier = Modifier.width(252.dp),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                ) {
                    MoviePostPlayButton(
                        label = stringResource(R.string.player_post_play_play),
                        painter = playPainter,
                        primary = true,
                        onClick = { onPlay(recommendation) },
                        focusRequester = playFocusRequester,
                        modifier = Modifier.focusProperties {
                            if (recommendation.hasTrailer) down = trailerFocusRequester
                        }
                    )

                    if (recommendation.hasTrailer) {
                        MoviePostPlayButton(
                            label = trailerButtonLabel(state),
                            painter = trailerPainter,
                            primary = false,
                            onClick = onPlayTrailer,
                            focusRequester = trailerFocusRequester,
                            modifier = Modifier.focusProperties { up = playFocusRequester }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun trailerButtonLabel(state: MoviePostPlayUiState): String {
    return when {
        state.isTrailerPlaying -> stringResource(R.string.player_post_play_trailer_playing)
        state.countdownSeconds != null -> stringResource(
            R.string.player_post_play_trailer_countdown,
            state.countdownSeconds
        )
        else -> stringResource(R.string.hero_play_trailer)
    }
}

@Composable
private fun MoviePostPlayButton(
    label: String,
    painter: Painter,
    primary: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.spacing.xxl)
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) Color.White else NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = if (primary) Color.White else NuvioTheme.colors.Secondary,
            contentColor = if (primary) Color.Black else NuvioTheme.colors.TextPrimary,
            focusedContentColor = if (primary) Color.Black else NuvioTheme.colors.OnSecondary
        ),
        shape = ButtonDefaults.shape(shape = shape),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = shape
            )
        ),
        contentPadding = PaddingValues(
            horizontal = NuvioTheme.spacing.xl,
            vertical = 14.dp
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            AnimatedContent(
                targetState = label,
                transitionSpec = {
                    fadeIn(tween(140)) togetherWith fadeOut(tween(100))
                },
                label = "moviePostPlayButtonLabel"
            ) { currentLabel ->
                Text(
                    text = currentLabel,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun rememberMoviePostPlayIcon(@RawRes iconRes: Int): Painter {
    val context = LocalContext.current
    val density = LocalDensity.current
    val sizePx = remember(density) { with(density) { NuvioTheme.spacing.xl.roundToPx() } }
    val request = remember(iconRes, context, sizePx) {
        ImageRequest.Builder(context)
            .data(iconRes)
            .size(sizePx)
            .build()
    }
    return rememberAsyncImagePainter(model = request)
}

private fun MoviePostPlayRecommendation.metadataLine(context: android.content.Context): String {
    return buildList {
        genres.take(2)
            .map { localizedGenreLabel(context, it) }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" • ")
            ?.let(::add)
        releaseInfo?.takeIf { it.isNotBlank() }?.let(::add)
        formatHeroRuntime(runtime)?.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString("  •  ")
}

@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.annotation.RawRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
import com.nuvio.tv.ui.components.ImdbRatingSourceLabel
import com.nuvio.tv.ui.components.MDBListRatingsRow
import com.nuvio.tv.ui.components.PlayManualOverrideDialog
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.formatHeroRuntime
import com.nuvio.tv.ui.util.localizedGenreLabel
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import java.util.Locale

@Composable
fun MoviePostPlayOverlay(
    state: MoviePostPlayUiState,
    currentTitle: String,
    showManualPlayOption: Boolean,
    playFocusRequester: FocusRequester,
    playerWindowFocusRequester: FocusRequester,
    onPlay: (MoviePostPlayRecommendation) -> Unit,
    onPlayManually: (MoviePostPlayRecommendation) -> Unit,
    onPlayTrailer: () -> Unit,
    onTrailerEnded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val recommendation = state.recommendation ?: return
    val trailerFocusRequester = remember(recommendation.id) { FocusRequester() }
    val context = LocalContext.current
    val imageLoader = context.imageLoader
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val playPainter = rememberMoviePostPlayIcon(R.raw.ic_player_play)
    val trailerPainter = rememberMoviePostPlayIcon(R.raw.trailer_play_button)
    var logoLoadFailed by remember(recommendation.logo) { mutableStateOf(false) }
    var showPlayOptionsDialog by remember(recommendation.id) { mutableStateOf(false) }
    val showLogo = !recommendation.logo.isNullOrBlank() && !logoLoadFailed
    val metadata = recommendation.metadataLine(context)
    val imdbRating = recommendation.rating
        ?.takeIf { recommendation.showStandardRatings && it > 0f }
    val tmdbRating = recommendation.tmdbRating
        ?.takeIf { recommendation.showStandardRatings && it > 0f }
    val logoHeight by animateDpAsState(
        targetValue = if (state.isTrailerPlaying) 60.dp else 92.dp,
        animationSpec = tween(600),
        label = "moviePostPlayLogoHeight"
    )
    val logoMaxWidth by animateFloatAsState(
        targetValue = if (state.isTrailerPlaying) 0.48f else 0.76f,
        animationSpec = tween(600),
        label = "moviePostPlayLogoWidth"
    )
    val actionTopSpacing by animateDpAsState(
        targetValue = if (state.isTrailerPlaying) NuvioTheme.spacing.md else NuvioTheme.spacing.xl,
        animationSpec = tween(600),
        label = "moviePostPlayActionSpacing"
    )
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

    LaunchedEffect(
        recommendation.id,
        state.isVisible,
        state.isTrailerPlaying,
        showPlayOptionsDialog
    ) {
        if (!state.isVisible || showPlayOptionsDialog) return@LaunchedEffect
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
                    .animateContentSize(animationSpec = tween(600))
                    .padding(
                        start = NuvioTheme.spacing.screen.overscanHorizontal,
                        bottom = NuvioTheme.spacing.screen.overscanVertical
                    ),
                horizontalAlignment = Alignment.Start
            ) {
                AnimatedVisibility(
                    visible = !state.isTrailerPlaying,
                    enter = fadeIn(tween(600)) + expandVertically(expandFrom = Alignment.Bottom),
                    exit = fadeOut(tween(240)) + shrinkVertically(shrinkTowards = Alignment.Bottom)
                ) {
                    Column {
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
                    }
                }

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
                            .fillMaxWidth(logoMaxWidth)
                            .heightIn(max = logoHeight)
                    )
                } else {
                    AnimatedContent(
                        targetState = state.isTrailerPlaying,
                        transitionSpec = {
                            fadeIn(tween(600)) togetherWith fadeOut(tween(240))
                        },
                        label = "moviePostPlayTitleSize"
                    ) { trailerPlaying ->
                        Text(
                            text = recommendation.title,
                            style = if (trailerPlaying) {
                                MaterialTheme.typography.headlineMedium
                            } else {
                                MaterialTheme.typography.displayMedium
                            },
                            color = NuvioTheme.colors.TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                AnimatedVisibility(
                    visible = !state.isTrailerPlaying,
                    enter = fadeIn(tween(600)) + expandVertically(expandFrom = Alignment.Bottom),
                    exit = fadeOut(tween(240)) + shrinkVertically(shrinkTowards = Alignment.Bottom)
                ) {
                    Column {
                        if (metadata.isNotBlank() || imdbRating != null || tmdbRating != null) {
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
                                if (metadata.isNotBlank() && (imdbRating != null || tmdbRating != null)) {
                                    Text(
                                        text = "•",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = NuvioTheme.extendedColors.textTertiary
                                    )
                                }
                                if (imdbRating != null || tmdbRating != null) {
                                    StandardRatingsRow(
                                        imdbRating = imdbRating,
                                        tmdbRating = tmdbRating
                                    )
                                }
                            }
                        }

                        recommendation.mdbListRatings
                            ?.takeUnless { it.isEmpty() }
                            ?.let { ratings ->
                                Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                                MDBListRatingsRow(ratings = ratings)
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
                    }
                }

                Spacer(modifier = Modifier.height(actionTopSpacing))

                BoxWithConstraints(modifier = Modifier.fillMaxWidth(0.92f)) {
                    val buttonSpacing = NuvioTheme.spacing.md
                    val buttonWidth = (maxWidth - buttonSpacing) / 2
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MoviePostPlayButton(
                            label = stringResource(R.string.player_post_play_play),
                            painter = playPainter,
                            primary = true,
                            onClick = { onPlay(recommendation) },
                            onLongPress = if (showManualPlayOption) {
                                { showPlayOptionsDialog = true }
                            } else {
                                null
                            },
                            focusRequester = playFocusRequester,
                            modifier = Modifier
                                .width(buttonWidth)
                                .focusProperties {
                                    if (!state.isTrailerPlaying && !state.hasAutoPlayedTrailer) {
                                        up = playerWindowFocusRequester
                                    }
                                }
                        )

                        AnimatedVisibility(
                            visible = recommendation.hasTrailer && !state.isTrailerPlaying,
                            enter = fadeIn(tween(600)) + expandHorizontally(expandFrom = Alignment.Start),
                            exit = fadeOut(tween(240)) + shrinkHorizontally(shrinkTowards = Alignment.Start)
                        ) {
                            Row {
                                Spacer(modifier = Modifier.width(buttonSpacing))
                                MoviePostPlayButton(
                                    label = trailerButtonLabel(state),
                                    painter = trailerPainter,
                                    primary = false,
                                    onClick = onPlayTrailer,
                                    focusRequester = trailerFocusRequester,
                                    modifier = Modifier
                                        .width(buttonWidth)
                                        .focusProperties {
                                            if (!state.hasAutoPlayedTrailer) {
                                                up = playerWindowFocusRequester
                                            }
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.isVisible && showManualPlayOption && showPlayOptionsDialog) {
        PlayManualOverrideDialog(
            title = recommendation.title,
            subtitle = stringResource(R.string.hero_play),
            onDismiss = { showPlayOptionsDialog = false },
            onPlayManually = {
                showPlayOptionsDialog = false
                onPlayManually(recommendation)
            }
        )
    }
}

@Composable
private fun StandardRatingsRow(
    imdbRating: Float?,
    tmdbRating: Float?
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        imdbRating?.let { rating ->
            ImdbRatingSourceLabel(
                logoModifier = Modifier.size(30.dp),
                textStyle = MaterialTheme.typography.labelLarge,
                textColor = NuvioTheme.extendedColors.textSecondary
            )
            Text(
                text = String.format(Locale.US, "%.1f", rating),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.extendedColors.textSecondary
            )
        }
        if (imdbRating != null && tmdbRating != null) {
            Text(
                text = "•",
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.extendedColors.textTertiary
            )
        }
        tmdbRating?.let { rating ->
            AsyncImage(
                model = R.raw.mdblist_tmdb,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(NuvioTheme.spacing.xl)
            )
            Text(
                text = (rating * 10).toInt().toString(),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.extendedColors.textSecondary
            )
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
    onLongPress: (() -> Unit)? = null,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.spacing.xxl)
    var longPressTriggered by remember { mutableStateOf(false) }
    val longPressKeyTracker = rememberLongPressKeyTracker()
    Button(
        onClick = {
            if (longPressTriggered) {
                longPressTriggered = false
            } else {
                onClick()
            }
        },
        modifier = modifier
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (onLongPress != null &&
                    native.action == AndroidKeyEvent.ACTION_DOWN &&
                    native.keyCode == AndroidKeyEvent.KEYCODE_MENU
                ) {
                    longPressTriggered = true
                    onLongPress()
                    return@onPreviewKeyEvent true
                }
                if (onLongPress != null &&
                    longPressKeyTracker.handle(native, ::isSelectKey) {
                        longPressTriggered = true
                        onLongPress()
                    }
                ) {
                    if (native.action == AndroidKeyEvent.ACTION_UP) {
                        longPressTriggered = false
                    }
                    return@onPreviewKeyEvent true
                }
                if (native.action == AndroidKeyEvent.ACTION_UP &&
                    longPressTriggered &&
                    isSelectOrMenuKey(native.keyCode)
                ) {
                    longPressTriggered = false
                    return@onPreviewKeyEvent true
                }
                false
            },
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

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}

private fun isSelectOrMenuKey(keyCode: Int): Boolean {
    return isSelectKey(keyCode) || keyCode == AndroidKeyEvent.KEYCODE_MENU
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

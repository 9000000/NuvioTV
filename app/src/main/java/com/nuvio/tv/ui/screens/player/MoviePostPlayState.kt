package com.nuvio.tv.ui.screens.player

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.MDBListRatings
import kotlin.math.ceil

@Immutable
data class MoviePostPlayRecommendation(
    val id: String,
    val contentType: String,
    val title: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val rating: Float?,
    val genres: List<String>,
    val runtime: String?,
    val tmdbId: String? = null,
    val tmdbRating: Float? = null,
    val ageRating: String? = null,
    val status: String? = null,
    val country: String? = null,
    val language: String? = null,
    val contentLanguage: String? = null,
    val mdbListRatings: MDBListRatings? = null,
    val showStandardRatings: Boolean = true,
    val trailerVideoUrl: String? = null,
    val trailerAudioUrl: String? = null
) {
    val hasTrailer: Boolean
        get() = !trailerVideoUrl.isNullOrBlank()
}

@Immutable
data class MoviePostPlayUiState(
    val recommendation: MoviePostPlayRecommendation? = null,
    val isLoadingRecommendation: Boolean = false,
    val isLoadingTrailer: Boolean = false,
    val isVisible: Boolean = false,
    val countdownSeconds: Int? = null,
    val isTrailerPlaying: Boolean = false,
    val hasAutoPlayedTrailer: Boolean = false
) {
    val blocksNaturalCompletion: Boolean
        get() = recommendation != null || isVisible || isLoadingRecommendation
}

internal const val MOVIE_POST_PLAY_PREFETCH_PROGRESS = 0.9f
internal const val MOVIE_POST_PLAY_PREFETCH_REMAINING_MS = 10 * 60_000L
internal const val MOVIE_POST_PLAY_TRAILER_COUNTDOWN_SECONDS = 5
internal const val MOVIE_POST_PLAY_TRANSITION_MS = 420

internal fun shouldPrefetchMoviePostPlay(
    positionMs: Long,
    durationMs: Long
): Boolean {
    if (durationMs <= 0L) return false
    val position = positionMs.coerceIn(0L, durationMs)
    val remaining = durationMs - position
    val progress = position.toDouble() / durationMs.toDouble()
    return progress >= MOVIE_POST_PLAY_PREFETCH_PROGRESS ||
        remaining <= MOVIE_POST_PLAY_PREFETCH_REMAINING_MS
}

internal fun moviePostPlayCountdownSeconds(
    positionMs: Long,
    durationMs: Long
): Int? {
    if (durationMs <= 0L) return null
    val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
    if (remainingMs > MOVIE_POST_PLAY_TRAILER_COUNTDOWN_SECONDS * 1_000L) return null
    return ceil(remainingMs / 1_000.0)
        .toInt()
        .coerceIn(1, MOVIE_POST_PLAY_TRAILER_COUNTDOWN_SECONDS)
}

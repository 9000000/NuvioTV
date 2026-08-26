package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.TrailerPlayerPool
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.util.isUnreleased
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.MoreLikeThisSourcePreference
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.repository.TraktRelatedService
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.MetaRepository
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class MoviePostPlayController(
    private val playbackController: PlayerRuntimeController,
    private val metaRepository: MetaRepository,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val traktRelatedService: TraktRelatedService,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val trailerService: TrailerService,
    private val trailerSettingsDataStore: TrailerSettingsDataStore,
    private val trailerPlayerPool: TrailerPlayerPool,
    private val scope: CoroutineScope
) {
    private data class PlaybackSnapshot(
        val contentType: String?,
        val hasError: Boolean,
        val hasBlockingOverlay: Boolean,
        val playbackEnded: Boolean,
        val positionMs: Long,
        val durationMs: Long
    )

    private val _uiState = MutableStateFlow(MoviePostPlayUiState())
    val uiState: StateFlow<MoviePostPlayUiState> = _uiState.asStateFlow()

    private var recommendationJob: Job? = null
    private var postEndCountdownJob: Job? = null
    private var recommendationLoadAttempted = false
    private var autoPlayTrailerEnabled = true
    private var lastSnapshot: PlaybackSnapshot? = null

    init {
        scope.launch {
            combine(
                playbackController.uiState,
                playbackController.playbackTimeline
            ) { playerState, timeline ->
                PlaybackSnapshot(
                    contentType = playerState.contentType,
                    hasError = !playerState.error.isNullOrBlank(),
                    hasBlockingOverlay = playerState.showPauseOverlay ||
                        playerState.showStreamInfoOverlay ||
                        playerState.showEpisodesPanel ||
                        playerState.showSourcesPanel ||
                        playerState.showAudioOverlay ||
                        playerState.showSubtitleOverlay ||
                        playerState.showSubtitleStylePanel ||
                        playerState.showSubtitleDelayOverlay ||
                        playerState.showSubtitleTimingDialog ||
                        playerState.showSpeedDialog ||
                        playerState.showMoreDialog,
                    playbackEnded = playerState.playbackEnded,
                    positionMs = timeline.currentPosition,
                    durationMs = timeline.duration
                )
            }
                .distinctUntilChanged()
                .collect { snapshot ->
                    lastSnapshot = snapshot
                    evaluate(snapshot)
                }
        }
    }

    fun playTrailer() {
        startTrailer()
    }

    fun onTrailerEnded() {
        trailerPlayerPool.stop()
        _uiState.update {
            it.copy(
                countdownSeconds = null,
                isTrailerPlaying = false,
                hasAutoPlayedTrailer = true
            )
        }
    }

    fun stop() {
        recommendationJob?.cancel()
        recommendationJob = null
        postEndCountdownJob?.cancel()
        postEndCountdownJob = null
        recommendationLoadAttempted = false
        autoPlayTrailerEnabled = true
        if (_uiState.value.isTrailerPlaying) {
            trailerPlayerPool.stop()
        }
        _uiState.value = MoviePostPlayUiState()
    }

    private fun evaluate(snapshot: PlaybackSnapshot) {
        if (!snapshot.contentType.isMovieContent() || snapshot.hasError) {
            if (_uiState.value.recommendation != null ||
                _uiState.value.isVisible ||
                _uiState.value.isLoadingRecommendation
            ) {
                stop()
            }
            return
        }

        val effectiveDuration = snapshot.durationMs
            .takeIf { it > 0L }
            ?: playbackController.lastKnownDuration
        if (isShortPlaceholderDuration(effectiveDuration)) return

        if (!recommendationLoadAttempted &&
            shouldPrefetchMoviePostPlay(snapshot.positionMs, effectiveDuration)
        ) {
            loadRecommendation()
        }

        var state = _uiState.value
        val recommendation = state.recommendation ?: return
        val shouldShow = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
            positionMs = snapshot.positionMs,
            durationMs = effectiveDuration,
            skipIntervals = playbackController.skipIntervals,
            thresholdMode = playbackController.nextEpisodeThresholdModeSetting,
            thresholdPercent = playbackController.nextEpisodeThresholdPercentSetting,
            thresholdMinutesBeforeEnd = playbackController.nextEpisodeThresholdMinutesBeforeEndSetting
        ) || snapshot.playbackEnded

        if (!shouldShow) return
        if (!state.isVisible && snapshot.hasBlockingOverlay) return

        if (!state.isVisible) {
            val needsPostEndCountdown = snapshot.playbackEnded &&
                recommendation.hasTrailer &&
                autoPlayTrailerEnabled
            _uiState.update {
                it.copy(
                    isVisible = true,
                    countdownSeconds = if (needsPostEndCountdown) {
                        MOVIE_POST_PLAY_TRAILER_COUNTDOWN_SECONDS
                    } else {
                        moviePostPlayCountdownSeconds(snapshot.positionMs, effectiveDuration)
                    }
                )
            }
            state = _uiState.value
            if (needsPostEndCountdown) {
                startPostEndCountdown()
                return
            }
        }

        if (state.isTrailerPlaying || state.hasAutoPlayedTrailer || !recommendation.hasTrailer) return
        if (!autoPlayTrailerEnabled) {
            if (state.countdownSeconds != null) {
                _uiState.update { it.copy(countdownSeconds = null) }
            }
            return
        }

        if (snapshot.playbackEnded) {
            if (state.countdownSeconds != null) {
                startTrailer()
            } else {
                startPostEndCountdown()
            }
            return
        }

        val countdown = moviePostPlayCountdownSeconds(snapshot.positionMs, effectiveDuration)
        if (countdown != state.countdownSeconds) {
            _uiState.update { it.copy(countdownSeconds = countdown) }
        }
    }

    private fun loadRecommendation() {
        recommendationLoadAttempted = true
        recommendationJob = scope.launch {
            _uiState.update { it.copy(isLoadingRecommendation = true) }
            val candidate = try {
                loadCurrentMeta()?.let { loadCandidate(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (candidate == null) {
                _uiState.update { it.copy(isLoadingRecommendation = false) }
                recommendationJob = null
                return@launch
            }

            val recommendation = resolveCandidate(candidate)
            autoPlayTrailerEnabled = runCatching {
                trailerSettingsDataStore.settings.first().enabled
            }.getOrDefault(true)
            _uiState.update {
                it.copy(
                    recommendation = recommendation,
                    isLoadingRecommendation = false,
                    isLoadingTrailer = true
                )
            }
            lastSnapshot?.let(::evaluate)

            val trailerSource = try {
                withTimeoutOrNull(15_000L) {
                    trailerService.getTrailerPlaybackSource(
                        title = recommendation.title,
                        year = recommendation.releaseInfo,
                        tmdbId = recommendation.tmdbId,
                        type = recommendation.contentType
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            _uiState.update { state ->
                state.copy(
                    recommendation = state.recommendation?.copy(
                        trailerVideoUrl = trailerSource?.videoUrl,
                        trailerAudioUrl = trailerSource?.audioUrl
                    ),
                    isLoadingTrailer = false
                )
            }
            recommendationJob = null
            lastSnapshot?.let(::evaluate)
        }
    }

    private suspend fun loadCurrentMeta(): Meta? {
        val id = playbackController.contentId ?: return null
        val type = playbackController.contentType ?: return null
        metaRepository.getCachedMeta(type, id)?.let { return it }
        return withTimeoutOrNull(8_000L) {
            when (
                val result = metaRepository.getMetaFromAllAddons(type = type, id = id)
                    .first { it !is NetworkResult.Loading }
            ) {
                is NetworkResult.Success -> result.data
                else -> null
            }
        }
    }

    private suspend fun loadCandidate(meta: Meta): MetaPreview? {
        val candidates = withTimeoutOrNull(10_000L) {
            val sourcePreference = traktSettingsDataStore.moreLikeThisSource.first()
            val traktAuthenticated = traktAuthDataStore.isAuthenticated.first()
            if (sourcePreference == MoreLikeThisSourcePreference.TRAKT && traktAuthenticated) {
                runCatching {
                    traktRelatedService.getRelated(
                        meta = meta,
                        fallbackItemId = playbackController.contentId,
                        fallbackItemType = playbackController.contentType
                    )
                }.getOrDefault(emptyList())
            } else {
                val settings = tmdbSettingsDataStore.settings.first()
                if (!settings.enabled || !settings.useMoreLikeThis) return@withTimeoutOrNull emptyList()
                val tmdbId = tmdbService.ensureTmdbId(meta.id, "movie")
                    ?: playbackController.contentId?.let { tmdbService.ensureTmdbId(it, "movie") }
                    ?: return@withTimeoutOrNull emptyList()
                runCatching {
                    tmdbMetadataService.fetchMoreLikeThis(
                        tmdbId = tmdbId,
                        contentType = com.nuvio.tv.domain.model.ContentType.MOVIE,
                        language = settings.language,
                        maxItems = 4
                    )
                }.getOrDefault(emptyList())
            }
        }.orEmpty()

        val hideUnreleased = layoutPreferenceDataStore.hideUnreleasedContent.first()
        val currentIds = setOfNotNull(meta.id.normalizedId(), playbackController.contentId?.normalizedId())
        val filtered = candidates
            .asSequence()
            .filterNot { it.id.normalizedId() in currentIds }
            .filterNot { hideUnreleased && it.isUnreleased(LocalDate.now()) }
            .toList()
        return filtered.firstOrNull { !it.backdropUrl.isNullOrBlank() }
            ?: filtered.firstOrNull()
    }

    private suspend fun resolveCandidate(candidate: MetaPreview): MoviePostPlayRecommendation {
        val settings = tmdbSettingsDataStore.settings.first()
        val meta = try {
            loadCandidateMeta(candidate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val tmdbId = try {
            tmdbService.ensureTmdbId(
                videoId = meta?.id ?: candidate.id,
                mediaType = meta?.apiType ?: candidate.apiType
            ) ?: if (meta?.id != candidate.id) {
                tmdbService.ensureTmdbId(candidate.id, candidate.apiType)
            } else {
                null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val enrichment = if (settings.enabled && tmdbId != null) {
            try {
                withTimeoutOrNull(12_000L) {
                    tmdbMetadataService.fetchEnrichment(
                        tmdbId = tmdbId,
                        contentType = ContentType.MOVIE,
                        language = settings.language
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        return resolveMoviePostPlayRecommendation(
            candidate = candidate,
            meta = meta,
            enrichment = enrichment,
            settings = settings,
            tmdbId = tmdbId
        )
    }

    private suspend fun loadCandidateMeta(candidate: MetaPreview): Meta? {
        metaRepository.getCachedMeta(candidate.apiType, candidate.id)?.let { return it }
        return withTimeoutOrNull(8_000L) {
            when (
                val result = metaRepository.getMetaFromAllAddons(
                    type = candidate.apiType,
                    id = candidate.id,
                    sourceAddonBaseUrl = candidate.sourceAddonBaseUrl
                ).first { it !is NetworkResult.Loading }
            ) {
                is NetworkResult.Success -> result.data
                else -> null
            }
        }
    }

    private fun startPostEndCountdown() {
        val state = _uiState.value
        if (postEndCountdownJob?.isActive == true ||
            state.isTrailerPlaying ||
            state.hasAutoPlayedTrailer ||
            state.recommendation?.hasTrailer != true
        ) {
            return
        }
        postEndCountdownJob = scope.launch {
            for (seconds in MOVIE_POST_PLAY_TRAILER_COUNTDOWN_SECONDS downTo 1) {
                _uiState.update { it.copy(countdownSeconds = seconds) }
                delay(1_000L)
            }
            startTrailer()
        }
    }

    private fun startTrailer() {
        val state = _uiState.value
        if (state.isTrailerPlaying || state.recommendation?.hasTrailer != true) return
        postEndCountdownJob?.cancel()
        postEndCountdownJob = null
        playbackController.releasePlayer()
        trailerPlayerPool.reclaim()
        _uiState.update {
            it.copy(
                isVisible = true,
                countdownSeconds = null,
                isTrailerPlaying = true,
                hasAutoPlayedTrailer = true
            )
        }
    }
}

private fun String?.isMovieContent(): Boolean {
    return this?.trim()?.lowercase() in setOf("movie", "film")
}

private fun String.normalizedId(): String = trim().lowercase()

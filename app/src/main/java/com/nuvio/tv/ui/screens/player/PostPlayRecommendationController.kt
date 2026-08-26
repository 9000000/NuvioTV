package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.TrailerPlayerPool
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.util.isUnreleased
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.local.MoreLikeThisSourcePreference
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.repository.MDBListRepository
import com.nuvio.tv.data.repository.TraktRelatedService
import com.nuvio.tv.data.trailer.TrailerService
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

internal class PostPlayRecommendationController(
    private val playbackController: PlayerRuntimeController,
    private val metaRepository: MetaRepository,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val mdbListRepository: MDBListRepository,
    private val mdbListSettingsDataStore: MDBListSettingsDataStore,
    private val traktRelatedService: TraktRelatedService,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val trailerService: TrailerService,
    private val trailerSettingsDataStore: TrailerSettingsDataStore,
    private val trailerPlayerPool: TrailerPlayerPool,
    private val scope: CoroutineScope
) {
    private data class PlaybackIdentity(
        val contentType: String?,
        val contentId: String?,
        val videoId: String?,
        val season: Int?,
        val episode: Int?
    )

    private data class PlaybackSnapshot(
        val identity: PlaybackIdentity,
        val contentType: String?,
        val isNextEpisodeMetadataResolved: Boolean,
        val nextEpisodeHasAired: Boolean?,
        val hasError: Boolean,
        val hasBlockingOverlay: Boolean,
        val playbackEnded: Boolean,
        val positionMs: Long,
        val durationMs: Long
    )

    private data class ResolvedCandidate(
        val recommendation: PostPlayRecommendation,
        val meta: Meta?
    )

    private data class RatingPreferences(
        val isMdbListActive: Boolean,
        val showStandardRatings: Boolean
    )

    private val _uiState = MutableStateFlow(PostPlayRecommendationUiState())
    val uiState: StateFlow<PostPlayRecommendationUiState> = _uiState.asStateFlow()

    private var recommendationJob: Job? = null
    private var postEndCountdownJob: Job? = null
    private var dismissAnimationJob: Job? = null
    private var recommendationLoadAttempted = false
    private var autoPlayTrailerEnabled = true
    private var lastSnapshot: PlaybackSnapshot? = null
    private var lastPlaybackIdentity: PlaybackIdentity? = null
    private var dismissedForCurrentPlayback = false

    init {
        scope.launch {
            combine(
                playbackController.uiState,
                playbackController.playbackTimeline
            ) { playerState, timeline ->
                PlaybackSnapshot(
                    identity = PlaybackIdentity(
                        contentType = playerState.contentType?.trim()?.lowercase(),
                        contentId = playbackController.contentId,
                        videoId = playerState.currentVideoId,
                        season = playerState.currentSeason,
                        episode = playerState.currentEpisode
                    ),
                    contentType = playerState.contentType,
                    isNextEpisodeMetadataResolved = playerState.isNextEpisodeMetadataResolved,
                    nextEpisodeHasAired = playerState.nextEpisode?.hasAired,
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
                    if (lastPlaybackIdentity?.let { it != snapshot.identity } == true) {
                        clearRecommendationState()
                    }
                    lastPlaybackIdentity = snapshot.identity
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

    fun dismiss() {
        recommendationJob?.cancel()
        recommendationJob = null
        postEndCountdownJob?.cancel()
        postEndCountdownJob = null
        dismissAnimationJob?.cancel()
        dismissedForCurrentPlayback = true
        _uiState.update {
            it.copy(
                isVisible = false,
                countdownSeconds = null
            )
        }
        dismissAnimationJob = scope.launch {
            delay(POST_PLAY_RECOMMENDATION_TRANSITION_MS.toLong())
            _uiState.value = PostPlayRecommendationUiState()
            dismissAnimationJob = null
        }
    }

    fun stop() {
        clearRecommendationState()
        lastSnapshot = null
        lastPlaybackIdentity = null
    }

    private fun clearRecommendationState() {
        recommendationJob?.cancel()
        recommendationJob = null
        postEndCountdownJob?.cancel()
        postEndCountdownJob = null
        dismissAnimationJob?.cancel()
        dismissAnimationJob = null
        recommendationLoadAttempted = false
        autoPlayTrailerEnabled = true
        dismissedForCurrentPlayback = false
        if (_uiState.value.isTrailerPlaying) {
            trailerPlayerPool.stop()
        }
        _uiState.value = PostPlayRecommendationUiState()
    }

    private fun evaluate(snapshot: PlaybackSnapshot) {
        val shouldUseRecommendation = shouldUsePostPlayRecommendation(
            contentType = snapshot.contentType,
            isNextEpisodeMetadataResolved = snapshot.isNextEpisodeMetadataResolved,
            nextEpisodeHasAired = snapshot.nextEpisodeHasAired
        )
        if (!shouldUseRecommendation || snapshot.hasError) {
            if (_uiState.value.recommendation != null ||
                _uiState.value.isVisible ||
                _uiState.value.isLoadingRecommendation ||
                recommendationJob != null
            ) {
                clearRecommendationState()
            }
            return
        }

        if (dismissedForCurrentPlayback) return

        val effectiveDuration = snapshot.durationMs
            .takeIf { it > 0L }
            ?: playbackController.lastKnownDuration
        if (isShortPlaceholderDuration(effectiveDuration)) return

        if (!recommendationLoadAttempted &&
            shouldPrefetchPostPlayRecommendation(snapshot.positionMs, effectiveDuration)
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
                        POST_PLAY_RECOMMENDATION_TRAILER_COUNTDOWN_SECONDS
                    } else {
                        postPlayRecommendationCountdownSeconds(snapshot.positionMs, effectiveDuration)
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

        val countdown = postPlayRecommendationCountdownSeconds(snapshot.positionMs, effectiveDuration)
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

            val resolvedCandidate = resolveCandidate(candidate)
            val ratingPreferences = loadRatingPreferences()
            val recommendation = resolvedCandidate.recommendation.copy(
                showStandardRatings = ratingPreferences.showStandardRatings
            )
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

            val ratingsJob = launch {
                val ratings = loadRatings(
                    candidate = candidate,
                    meta = resolvedCandidate.meta,
                    enabled = ratingPreferences.isMdbListActive
                )
                _uiState.update { state ->
                    state.copy(
                        recommendation = state.recommendation?.copy(
                            mdbListRatings = ratings
                        )
                    )
                }
            }

            val trailerJob = launch {
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
                lastSnapshot?.let(::evaluate)
            }

            ratingsJob.join()
            trailerJob.join()
            recommendationJob = null
        }
    }

    private suspend fun loadRatingPreferences(): RatingPreferences {
        val settings = mdbListSettingsDataStore.settings.first()
        val isMdbListActive = settings.enabled && settings.apiKey.isNotBlank()
        val visibility = layoutPreferenceDataStore.homeImdbRatingsVisibility.first()
        return RatingPreferences(
            isMdbListActive = isMdbListActive,
            showStandardRatings = visibility.showStandardDetailRatings(isMdbListActive)
        )
    }

    private suspend fun loadRatings(
        candidate: MetaPreview,
        meta: Meta?,
        enabled: Boolean
    ) = if (!enabled || meta == null) {
        null
    } else {
        try {
            mdbListRepository.getRatingsForMeta(
                meta = meta,
                fallbackItemId = candidate.id,
                fallbackItemType = candidate.apiType
            )?.ratings
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
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
        val tmdbContentType = resolvePostPlayContentType(
            apiType = playbackController.contentType,
            fallback = meta.type
        ) ?: return null
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
                val lookupType = tmdbContentType.toApiString(playbackController.contentType)
                val tmdbId = tmdbService.ensureTmdbId(meta.id, lookupType)
                    ?: playbackController.contentId?.let { tmdbService.ensureTmdbId(it, lookupType) }
                    ?: return@withTimeoutOrNull emptyList()
                runCatching {
                    tmdbMetadataService.fetchMoreLikeThis(
                        tmdbId = tmdbId,
                        contentType = tmdbContentType,
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

    private suspend fun resolveCandidate(candidate: MetaPreview): ResolvedCandidate {
        val settings = tmdbSettingsDataStore.settings.first()
        val meta = try {
            loadCandidateMeta(candidate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val candidateContentType = resolvePostPlayContentType(
            apiType = meta?.apiType ?: candidate.apiType,
            fallback = meta?.type ?: candidate.type
        )
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
        val enrichment = if (settings.enabled && tmdbId != null && candidateContentType != null) {
            try {
                withTimeoutOrNull(12_000L) {
                    tmdbMetadataService.fetchEnrichment(
                        tmdbId = tmdbId,
                        contentType = candidateContentType,
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
        return ResolvedCandidate(
            recommendation = resolvePostPlayRecommendation(
                candidate = candidate,
                meta = meta,
                enrichment = enrichment,
                settings = settings,
                tmdbId = tmdbId
            ),
            meta = meta
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
            for (seconds in POST_PLAY_RECOMMENDATION_TRAILER_COUNTDOWN_SECONDS downTo 1) {
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

private fun String.normalizedId(): String = trim().lowercase()

package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

@Singleton
class SimklTrackingProgressProvider @Inject constructor(
    private val syncRepository: SimklSyncRepository,
    private val apiClient: SimklApiClient,
    private val authStorage: SimklAuthStorage
) : TrackingProgressProvider {
    override val providerId = TrackingProviderId.SIMKL
    override val isAuthenticated = authStorage.state.map { state -> state.isAuthenticated }
        .distinctUntilChanged()
    override val allProgress = syncRepository.state.map { state ->
        state.snapshot.toSimklProgressEntries()
    }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
        .distinctUntilChanged()
    override val remoteProgressLoaded = syncRepository.state.map { state ->
        state.hasLoaded && state.errorMessage == null
    }.distinctUntilChanged()
    override val nextUpSeeds = syncRepository.state.map { state ->
        state.snapshot.toSimklWatchedProjection().items
            .filter { item -> item.season != null && item.episode != null && item.season != 0 }
            .groupBy(WatchedItem::contentId)
            .mapNotNull { (_, items) -> items.maxByOrNull(WatchedItem::watchedAt) }
            .map(WatchedItem::toCompletedProgress)
            .sortedByDescending(WatchProgress::lastWatched)
    }.distinctUntilChanged()
    override val watchedMovieIds = syncRepository.state.map { state ->
        val watched = state.snapshot.toSimklWatchedProjection().items
            .filter { item -> item.season == null && item.episode == null && item.contentType == "movie" }
            .mapTo(mutableSetOf(), WatchedItem::contentId)
        state.snapshot.toSimklProgressEntries()
            .filter { progress ->
                progress.contentType == "movie" && progress.progressPercentage > 0f && !progress.isCompleted()
            }
            .forEach { progress -> watched.remove(progress.contentId) }
        watched
    }.distinctUntilChanged()
    override val watchedItems = syncRepository.state.map { state ->
        state.snapshot.toSimklWatchedProjection().items
    }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
        .distinctUntilChanged()

    override fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
        syncRepository.state.map { state ->
            val completed = state.snapshot.toSimklWatchedProjection().items
                .filter { item ->
                    item.contentId.equals(contentId, ignoreCase = true) &&
                        item.season != null && item.episode != null
                }
                .associate { item ->
                    (requireNotNull(item.season) to requireNotNull(item.episode)) to item.toCompletedProgress()
                }
                .toMutableMap()
            state.snapshot.toSimklProgressEntries()
                .filter { progress ->
                    progress.contentId.equals(contentId, ignoreCase = true) &&
                        progress.season != null && progress.episode != null
                }
                .forEach { progress ->
                    completed[requireNotNull(progress.season) to requireNotNull(progress.episode)] = progress
                }
            completed
        }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
            .distinctUntilChanged()

    override fun airedEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> = flowOf(emptyList())

    override fun isWatched(
        contentId: String,
        videoId: String?,
        season: Int?,
        episode: Int?
    ): Flow<Boolean> = syncRepository.state.map { state ->
        val progress = state.snapshot.toSimklProgressEntries().firstOrNull { candidate ->
            candidate.contentId.equals(contentId, ignoreCase = true) &&
                candidate.season == season && candidate.episode == episode
        }
        if (progress != null && progress.progressPercentage > 0f && !progress.isCompleted()) {
            false
        } else {
            state.snapshot.toSimklWatchedProjection().items.any { item ->
                item.contentId.equals(contentId, ignoreCase = true) &&
                    item.season == season && item.episode == episode
            }
        }
    }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
        .distinctUntilChanged()

    override suspend fun watchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> {
        syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC)
        return syncRepository.state.value.snapshot.toSimklWatchedProjection().items
            .filter { item -> item.season != null && item.episode != null }
            .groupBy(WatchedItem::contentId)
            .mapValues { (_, items) ->
                items.map { item -> requireNotNull(item.season) to requireNotNull(item.episode) }.toSet()
            }
    }

    override suspend fun showIdSiblings(): Map<String, Set<String>> {
        syncRepository.ensureLoaded()
        val siblings = mutableMapOf<String, MutableSet<String>>()
        syncRepository.state.value.snapshot.entries.forEach { entry ->
            val ids = entry.media?.trackingContentIds().orEmpty()
            ids.forEach { id -> siblings.getOrPut(id) { linkedSetOf() }.addAll(ids - id) }
        }
        return siblings
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = syncRepository.refresh(intent)

    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        syncRepository.ensureLoaded()
        val sessions = syncRepository.state.value.snapshot.playback.filter { session ->
            val media = session.media ?: return@filter false
            val sameContent = media.canonicalContentId().equals(contentId, ignoreCase = true) ||
                syncRepository.state.value.snapshot.entries.any { entry ->
                    entry.media == media && entry.matchesSimklContentId(contentId)
                }
            val sessionSeason = session.episode?.tvdbSeason ?: session.episode?.season
            val sessionEpisode = session.episode?.tvdbNumber ?: session.episode?.number
            sameContent && (season == null || episode == null ||
                sessionSeason == season && sessionEpisode == episode)
        }
        val removed = linkedSetOf<Long>()
        sessions.mapNotNull(SimklPlaybackSession::id).forEach { sessionId ->
            try {
                apiClient.execute(
                    SimklApiRequest(
                        method = SimklHttpMethod.DELETE,
                        path = "/sync/playback/$sessionId"
                    )
                )
                removed += sessionId
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                Unit
            }
        }
        syncRepository.removePlaybackSessions(removed)
        if (removed.isNotEmpty()) syncRepository.refreshAsync(TrackingRefreshIntent.INVALIDATED)
    }

    override fun applyOptimisticProgress(progress: WatchProgress, quiet: Boolean) = Unit

    override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) = Unit

    override fun clearOptimistic() = Unit

    override fun isHiddenFromProgress(contentId: String): Boolean =
        syncRepository.state.value.snapshot.entries.any { entry ->
            entry.status == SimklListStatus.DROPPED && entry.matchesSimklContentId(contentId)
        }

    override suspend fun remapEpisodeSeed(progress: WatchProgress): WatchProgress = progress
}

private fun WatchedItem.toCompletedProgress(): WatchProgress = WatchProgress(
    contentId = contentId,
    contentType = contentType,
    name = title,
    poster = poster,
    backdrop = null,
    logo = null,
    videoId = if (season != null && episode != null) "$contentId:$season:$episode" else contentId,
    season = season,
    episode = episode,
    episodeTitle = null,
    position = 1L,
    duration = 1L,
    lastWatched = watchedAt,
    progressPercent = 100f,
    source = WatchProgress.SOURCE_SIMKL_PLAYBACK,
    trackingProviderId = trackingProviderId,
    trackingProviderItemId = trackingProviderItemId,
    trackingSourceUrl = trackingSourceUrl
)

private fun SimklMedia.trackingContentIds(): Set<String> = buildSet {
    canonicalContentId()?.let(::add)
    ids.idValue("imdb")?.let(::add)
    ids.idValue("tmdb")?.let { add("tmdb:$it") }
    ids.idValue("tvdb")?.let { add("tvdb:$it") }
    ids.idValue("mal")?.let { add("mal:$it") }
    ids.idValue("anidb")?.let { add("anidb:$it") }
    ids.idValue("anilist")?.let { add("anilist:$it") }
    ids.idValue("kitsu")?.let { add("kitsu:$it") }
    ids.simklIdValue()?.let { add("simkl:$it") }
}

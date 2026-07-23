package com.nuvio.tv.data.simkl

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem

internal fun SimklSyncSnapshot.reconcileWatchedPlayback(): SimklSyncSnapshot {
    if (entries.isEmpty() || playback.isEmpty()) return this
    val watchedItems = toSimklWatchedProjection().items
    if (watchedItems.isEmpty()) return this
    val retainedPlayback = playback.filterNot { session ->
        session.toWatchProgress()?.let { progress ->
            watchedItems.any { watched -> watched.supersedes(progress) }
        } == true
    }
    return if (retainedPlayback.size == playback.size) this else copy(playback = retainedPlayback)
}

private fun WatchedItem.supersedes(progress: WatchProgress): Boolean {
    if (!contentType.equals(progress.contentType, ignoreCase = true)) return false
    if (season != progress.season || episode != progress.episode) return false
    val sameProviderItem = trackingProviderItemId
        ?.takeIf(String::isNotBlank)
        ?.equals(progress.trackingProviderItemId, ignoreCase = true) == true
    val sameContent = contentId.equals(progress.contentId, ignoreCase = true)
    return (sameProviderItem || sameContent) && watchedAt >= progress.lastWatched
}

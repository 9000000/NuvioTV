package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.WatchProgress

internal fun mergeProgressProjectionWithRetainedLocal(
    providerEntries: List<WatchProgress>,
    localEntries: List<WatchProgress>,
    retainsLocalProgress: (String) -> Boolean
): List<WatchProgress> {
    val providerKeys = providerEntries.mapTo(mutableSetOf(), WatchProgress::projectionKey)
    return buildList {
        addAll(providerEntries)
        localEntries.forEach { progress ->
            if (retainsLocalProgress(progress.contentId) && progress.projectionKey() !in providerKeys) {
                add(progress)
            }
        }
    }.sortedByDescending(WatchProgress::lastWatched)
}

private fun WatchProgress.projectionKey() = Triple(contentId, season, episode)

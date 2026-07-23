package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingProgressProjectionTest {
    @Test
    fun `projection keeps eligible local entries without replacing provider entries`() {
        val providerEntry = progress("tt100", 1, 1, 10L, 25L)
        val duplicateLocal = progress("tt100", 1, 1, 30L, 75L)
        val retainedLocal = progress("kitsu:44", 1, 2, 20L, 50L)
        val excludedLocal = progress("tt200", null, null, 40L, 60L)

        val result = mergeProgressProjectionWithRetainedLocal(
            providerEntries = listOf(providerEntry),
            localEntries = listOf(duplicateLocal, retainedLocal, excludedLocal),
            retainsLocalProgress = { contentId -> contentId.startsWith("kitsu:") }
        )

        assertEquals(listOf(retainedLocal, providerEntry), result)
    }

    @Test
    fun `projection stays provider exclusive when no local entries are retained`() {
        val providerEntry = progress("simkl:100", null, null, 10L, 25L)
        val localEntry = progress("kitsu:44", null, null, 20L, 50L)

        val result = mergeProgressProjectionWithRetainedLocal(
            providerEntries = listOf(providerEntry),
            localEntries = listOf(localEntry),
            retainsLocalProgress = { false }
        )

        assertEquals(listOf(providerEntry), result)
    }

    private fun progress(
        contentId: String,
        season: Int?,
        episode: Int?,
        lastWatched: Long,
        position: Long
    ) = WatchProgress(
        contentId = contentId,
        contentType = if (season == null) "movie" else "series",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = season,
        episode = episode,
        episodeTitle = null,
        position = position,
        duration = 100L,
        lastWatched = lastWatched
    )
}

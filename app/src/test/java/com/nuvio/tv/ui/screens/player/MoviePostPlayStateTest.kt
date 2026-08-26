package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.tmdb.TmdbEnrichment
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoviePostPlayStateTest {
    @Test
    fun `prefetches in final ten minutes`() {
        assertTrue(
            shouldPrefetchMoviePostPlay(
                positionMs = 6_600_000L,
                durationMs = 7_200_000L
            )
        )
    }

    @Test
    fun `does not prefetch early in playback`() {
        assertFalse(
            shouldPrefetchMoviePostPlay(
                positionMs = 1_800_000L,
                durationMs = 7_200_000L
            )
        )
    }

    @Test
    fun `countdown follows final playback seconds`() {
        assertEquals(5, moviePostPlayCountdownSeconds(95_200L, 100_000L))
        assertEquals(2, moviePostPlayCountdownSeconds(98_400L, 100_000L))
        assertEquals(1, moviePostPlayCountdownSeconds(100_000L, 100_000L))
    }

    @Test
    fun `countdown stays hidden before final five seconds`() {
        assertNull(moviePostPlayCountdownSeconds(94_900L, 100_000L))
    }

    @Test
    fun `loaded recommendation holds natural completion until overlay evaluation`() {
        val recommendation = MoviePostPlayRecommendation(
            id = "tmdb:1",
            contentType = "movie",
            title = "Example",
            poster = null,
            backdrop = null,
            logo = null,
            description = null,
            releaseInfo = null,
            rating = null,
            genres = emptyList(),
            runtime = null
        )

        assertTrue(MoviePostPlayUiState(recommendation = recommendation).blocksNaturalCompletion)
    }

    @Test
    fun `resolved recommendation uses detail enrichment artwork and title`() {
        val recommendation = resolveMoviePostPlayRecommendation(
            candidate = preview(
                background = "https://image/candidate-backdrop.jpg",
                logo = null
            ),
            meta = null,
            enrichment = enrichment(
                title = "Localized title",
                backdrop = "https://image/detail-backdrop.jpg",
                logo = "https://image/title-logo.png"
            ),
            settings = TmdbSettings(
                enabled = true,
                useArtwork = true,
                useBasicInfo = true,
                useDetails = true,
                useReleaseDates = true
            ),
            tmdbId = "42"
        )

        assertEquals("Localized title", recommendation.title)
        assertEquals("https://image/detail-backdrop.jpg", recommendation.backdrop)
        assertEquals("https://image/title-logo.png", recommendation.logo)
        assertEquals("42", recommendation.tmdbId)
    }

    @Test
    fun `resolved recommendation respects disabled artwork enrichment`() {
        val recommendation = resolveMoviePostPlayRecommendation(
            candidate = preview(
                background = "https://image/addon-backdrop.jpg",
                logo = "https://image/addon-logo.png"
            ),
            meta = null,
            enrichment = enrichment(
                title = "Localized title",
                backdrop = "https://image/tmdb-backdrop.jpg",
                logo = "https://image/tmdb-logo.png"
            ),
            settings = TmdbSettings(enabled = true, useArtwork = false),
            tmdbId = "42"
        )

        assertEquals("https://image/addon-backdrop.jpg", recommendation.backdrop)
        assertEquals("https://image/addon-logo.png", recommendation.logo)
    }

    private fun preview(background: String, logo: String?): MetaPreview {
        return MetaPreview(
            id = "tmdb:42",
            type = ContentType.MOVIE,
            name = "Candidate title",
            poster = "https://image/poster.jpg",
            posterShape = PosterShape.LANDSCAPE,
            background = background,
            logo = logo,
            description = "Candidate description",
            releaseInfo = "2025",
            imdbRating = 7.5f,
            genres = listOf("Drama"),
            runtime = "120"
        )
    }

    private fun enrichment(
        title: String,
        backdrop: String,
        logo: String
    ): TmdbEnrichment {
        return TmdbEnrichment(
            localizedTitle = title,
            description = "Localized description",
            genres = listOf("Thriller"),
            backdrop = backdrop,
            logo = logo,
            poster = "https://image/tmdb-poster.jpg",
            directorMembers = emptyList(),
            writerMembers = emptyList(),
            castMembers = emptyList(),
            releaseInfo = "2026",
            rating = 8.2,
            runtimeMinutes = 118,
            director = emptyList(),
            writer = emptyList(),
            productionCompanies = emptyList(),
            networks = emptyList(),
            ageRating = "PG-13",
            status = "Released",
            countries = listOf("US"),
            language = "en",
            collectionId = null,
            collectionName = null
        )
    }
}

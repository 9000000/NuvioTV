package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.extractTrackingYear
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.core.tracking.trackingMediaKind
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem

data class SimklWatchedProjection(
    val items: List<WatchedItem>,
    val fullyWatchedSeriesKeys: Set<String>
)

fun SimklSyncSnapshot.toSimklWatchedProjection(): SimklWatchedProjection {
    val watchedItems = mutableListOf<WatchedItem>()
    val fullyWatched = linkedSetOf<String>()
    entries.forEach { entry ->
        val media = entry.media ?: return@forEach
        val contentId = media.canonicalContentId() ?: return@forEach
        val contentType = if (entry.mediaType == SimklMediaType.MOVIES) "movie" else "series"
        val title = media.title?.takeIf(String::isNotBlank) ?: contentId
        val watchedAt = parseSimklUtcEpochMs(entry.lastWatchedAt)
            ?: parseSimklUtcEpochMs(entry.addedToWatchlistAt)
            ?: 0L

        if (entry.mediaType == SimklMediaType.MOVIES) {
            if (entry.lastWatchedAt != null || entry.status == SimklListStatus.COMPLETED) {
                watchedItems += entry.toWatchedItem(contentId, contentType, title, media, watchedAt)
            }
            return@forEach
        }

        var hasEpisodeHistory = false
        entry.seasons.forEach { season ->
            season.episodes.forEach episodeLoop@ { episode ->
                val seasonNumber = episode.tvdb?.season ?: season.number ?: return@episodeLoop
                val episodeNumber = episode.tvdb?.episode ?: episode.number ?: return@episodeLoop
                val episodeWatchedAt = parseSimklUtcEpochMs(episode.watchedAt) ?: return@episodeLoop
                hasEpisodeHistory = true
                watchedItems += entry.toWatchedItem(
                    contentId,
                    contentType,
                    title,
                    media,
                    episodeWatchedAt,
                    seasonNumber,
                    episodeNumber
                )
            }
        }

        if (entry.status == SimklListStatus.COMPLETED) {
            fullyWatched += watchedKey(contentType, contentId)
            if (!hasEpisodeHistory) {
                watchedItems += entry.toWatchedItem(contentId, contentType, title, media, watchedAt)
            }
        }
    }
    return SimklWatchedProjection(
        items = watchedItems.groupBy { item ->
            watchedKey(item.contentType, item.contentId, item.season, item.episode)
        }.mapNotNull { (_, candidates) -> candidates.maxByOrNull(WatchedItem::watchedAt) }
            .sortedByDescending(WatchedItem::watchedAt),
        fullyWatchedSeriesKeys = fullyWatched
    )
}

fun SimklSyncSnapshot.toSimklProgressEntries(): List<WatchProgress> = playback
    .mapNotNull(SimklPlaybackSession::toWatchProgress)
    .groupBy(::simklProgressKey)
    .mapNotNull { (_, candidates) -> candidates.maxByOrNull(WatchProgress::lastWatched) }
    .sortedByDescending(WatchProgress::lastWatched)

fun SimklSyncSnapshot.mediaReference(
    contentId: String,
    contentType: String,
    title: String? = null,
    releaseInfo: String? = null,
    season: Int? = null,
    episode: Int? = null,
    episodeTitle: String? = null
): TrackingMediaReference {
    val entry = entries.firstOrNull { candidate -> candidate.matchesSimklContentId(contentId) }
    val media = entry?.media
    val parsedIds = parseTrackingExternalIds(contentId)
    val ids = media?.toTrackingExternalIds()?.mergeMissing(parsedIds) ?: parsedIds
    val kind = when (entry?.mediaType) {
        SimklMediaType.MOVIES -> TrackingMediaKind.MOVIE
        SimklMediaType.ANIME -> TrackingMediaKind.ANIME
        SimklMediaType.SHOWS -> TrackingMediaKind.SHOW
        null -> trackingMediaKind(contentType, ids)
    }
    return TrackingMediaReference(
        kind = kind,
        title = media?.title?.takeIf(String::isNotBlank) ?: title?.takeIf(String::isNotBlank),
        year = media?.year ?: extractTrackingYear(releaseInfo),
        ids = ids,
        episode = episode?.let { number -> TrackingEpisode(season, number, episodeTitle) }
    )
}

fun SimklSyncSnapshot.enrichMediaReference(reference: TrackingMediaReference): TrackingMediaReference {
    val entry = entries.firstOrNull { candidate ->
        candidate.media?.toTrackingExternalIds()?.sharesIdentityWith(reference.ids) == true
    } ?: return reference
    val media = entry.media ?: return reference
    val kind = when (entry.mediaType) {
        SimklMediaType.MOVIES -> TrackingMediaKind.MOVIE
        SimklMediaType.SHOWS -> TrackingMediaKind.SHOW
        SimklMediaType.ANIME -> TrackingMediaKind.ANIME
    }
    return reference.copy(
        kind = kind,
        title = media.title?.takeIf(String::isNotBlank) ?: reference.title,
        year = media.year ?: reference.year,
        ids = media.toTrackingExternalIds().mergeMissing(reference.ids)
    )
}

fun SimklMedia.toTrackingExternalIds(): TrackingExternalIds = TrackingExternalIds(
    simkl = ids.simklIdValue()?.toLongOrNull(),
    imdb = ids.idValue("imdb"),
    tmdb = ids.idValue("tmdb")?.toLongOrNull(),
    tvdb = ids.idValue("tvdb"),
    mal = ids.idValue("mal")?.toLongOrNull(),
    anidb = ids.idValue("anidb")?.toLongOrNull(),
    anilist = ids.idValue("anilist")?.toLongOrNull(),
    kitsu = ids.idValue("kitsu")?.toLongOrNull()
)

fun SimklMedia.canonicalContentId(): String? = when {
    !ids.idValue("imdb").isNullOrBlank() -> ids.idValue("imdb")
    !ids.idValue("tmdb").isNullOrBlank() -> "tmdb:${ids.idValue("tmdb")}"
    !ids.idValue("tvdb").isNullOrBlank() -> "tvdb:${ids.idValue("tvdb")}"
    !ids.idValue("mal").isNullOrBlank() -> "mal:${ids.idValue("mal")}"
    !ids.idValue("anidb").isNullOrBlank() -> "anidb:${ids.idValue("anidb")}"
    !ids.idValue("anilist").isNullOrBlank() -> "anilist:${ids.idValue("anilist")}"
    !ids.idValue("kitsu").isNullOrBlank() -> "kitsu:${ids.idValue("kitsu")}"
    !ids.simklIdValue().isNullOrBlank() -> "simkl:${ids.simklIdValue()}"
    else -> null
}

fun simklPosterUrl(path: String?): String? = path?.trim()?.trim('/')
    ?.takeIf(String::isNotBlank)
    ?.let { normalized -> "https://wsrv.nl/?url=https://simkl.in/posters/${normalized}_ca.webp&q=90" }

fun buildSimklSourceUrl(mediaType: SimklMediaType, media: SimklMedia): String? {
    val id = media.ids.simklIdValue()?.toLongOrNull()?.takeIf { it > 0L } ?: return null
    val category = when (mediaType) {
        SimklMediaType.MOVIES -> "movies"
        SimklMediaType.SHOWS -> "tv"
        SimklMediaType.ANIME -> "anime"
    }
    val slug = media.ids.idValue("slug")?.trim()?.trim('/')
        ?.takeIf { value -> value.isNotBlank() && value.all { it.isLetterOrDigit() || it in "-_." } }
    return if (slug == null) "https://simkl.com/$category/$id" else "https://simkl.com/$category/$id/$slug"
}

fun parseSimklUtcEpochMs(value: String?): Long? {
    val match = value?.trim()?.let(SIMKL_UTC_PATTERN::matchEntire) ?: return null
    val year = match.groupValues[1].toIntOrNull() ?: return null
    val month = match.groupValues[2].toIntOrNull() ?: return null
    val day = match.groupValues[3].toIntOrNull() ?: return null
    val hour = match.groupValues[4].toIntOrNull() ?: return null
    val minute = match.groupValues[5].toIntOrNull() ?: return null
    val second = match.groupValues[6].toIntOrNull() ?: return null
    val millis = match.groupValues[7].padEnd(3, '0').take(3).toIntOrNull() ?: 0
    if (year < 1970 || month !in 1..12 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
    val monthDays = daysInMonths(year)
    if (day !in 1..monthDays[month - 1]) return null
    var days = 0L
    for (currentYear in 1970 until year) days += if (currentYear.isLeapYear()) 366L else 365L
    for (monthIndex in 0 until month - 1) days += monthDays[monthIndex]
    days += day - 1L
    return (((days * 24L + hour) * 60L + minute) * 60L + second) * 1_000L + millis
}

fun SimklLibraryEntry.matchesSimklContentId(contentId: String): Boolean {
    val media = media ?: return false
    if (media.canonicalContentId().equals(contentId, ignoreCase = true)) return true
    val parsed = parseTrackingExternalIds(contentId)
    val candidateIds = media.toTrackingExternalIds()
    return parsed.sharesIdentityWith(candidateIds)
}

private fun SimklLibraryEntry.toWatchedItem(
    contentId: String,
    contentType: String,
    title: String,
    media: SimklMedia,
    watchedAt: Long,
    season: Int? = null,
    episode: Int? = null
): WatchedItem = WatchedItem(
    contentId = contentId,
    contentType = contentType,
    title = title,
    season = season,
    episode = episode,
    watchedAt = watchedAt,
    poster = simklPosterUrl(media.poster),
    releaseInfo = media.year?.toString(),
    trackingProviderId = TrackingProviderId.SIMKL.storageId,
    trackingProviderItemId = media.simklTrackingProviderItemId(),
    trackingSourceUrl = buildSimklSourceUrl(mediaType, media)
)

internal fun SimklPlaybackSession.toWatchProgress(): WatchProgress? {
    val media = media ?: return null
    val parentId = media.canonicalContentId() ?: return null
    val isMovie = mediaType == SimklMediaType.MOVIES
    val season = episode?.tvdbSeason ?: episode?.season
    val episodeNumber = episode?.tvdbNumber ?: episode?.number
    if (!isMovie && episodeNumber == null) return null
    val videoId = if (isMovie) parentId else "$parentId:${season ?: 0}:$episodeNumber"
    val normalizedProgress = progress.coerceIn(0.0, 100.0)
    val durationMs = media.runtime?.takeIf { it > 0 }?.toLong()?.times(60_000L) ?: 0L
    val positionMs = if (durationMs > 0L) (durationMs * normalizedProgress / 100.0).toLong() else 0L
    val updatedAt = parseSimklUtcEpochMs(pausedAt) ?: parseSimklUtcEpochMs(watchedAt) ?: 0L
    return WatchProgress(
        contentId = parentId,
        contentType = if (isMovie) "movie" else "series",
        name = media.title?.takeIf(String::isNotBlank) ?: parentId,
        poster = simklPosterUrl(media.poster),
        backdrop = null,
        logo = null,
        videoId = videoId,
        season = season,
        episode = episodeNumber,
        episodeTitle = episode?.title,
        position = positionMs,
        duration = durationMs,
        lastWatched = updatedAt,
        progressPercent = normalizedProgress.toFloat(),
        source = WatchProgress.SOURCE_SIMKL_PLAYBACK,
        simklPlaybackId = id,
        trackingProviderId = TrackingProviderId.SIMKL.storageId,
        trackingProviderItemId = media.simklTrackingProviderItemId(),
        trackingSourceUrl = buildSimklSourceUrl(mediaType, media)
    )
}

private fun simklProgressKey(progress: WatchProgress): String = progress.simklPlaybackId
    ?.let { "simkl-playback:$it" }
    ?: "${progress.contentId}:${progress.season ?: -1}:${progress.episode ?: -1}"

private fun SimklMedia.simklTrackingProviderItemId(): String? = ids.simklIdValue()
    ?.toLongOrNull()
    ?.takeIf { it > 0L }
    ?.let { id -> "simkl:$id" }

private fun TrackingExternalIds.sharesIdentityWith(other: TrackingExternalIds): Boolean =
    (simkl != null && simkl == other.simkl) ||
        (!imdb.isNullOrBlank() && imdb.equals(other.imdb, ignoreCase = true)) ||
        (tmdb != null && tmdb == other.tmdb) ||
        (!tvdb.isNullOrBlank() && tvdb.equals(other.tvdb, ignoreCase = true)) ||
        (mal != null && mal == other.mal) ||
        (anidb != null && anidb == other.anidb) ||
        (anilist != null && anilist == other.anilist) ||
        (kitsu != null && kitsu == other.kitsu)

private fun watchedKey(
    contentType: String,
    contentId: String,
    season: Int? = null,
    episode: Int? = null
): String = "${contentType.lowercase()}:${contentId.lowercase()}:${season ?: -1}:${episode ?: -1}"

private fun Int.isLeapYear(): Boolean = (this % 4 == 0 && this % 100 != 0) || this % 400 == 0

private fun daysInMonths(year: Int): IntArray = if (year.isLeapYear()) {
    intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
} else {
    intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
}

private val SIMKL_UTC_PATTERN = Regex(
    "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?Z$",
    RegexOption.IGNORE_CASE
)

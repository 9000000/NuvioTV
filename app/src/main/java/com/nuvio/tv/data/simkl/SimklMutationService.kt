package com.nuvio.tv.data.simkl

import android.util.Log
import com.nuvio.tv.core.tracking.TRACKING_SCROBBLE_DIAGNOSTIC_TAG
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingListStatus
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResolution
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.scrobbleDiagnosticSummary
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Singleton
class SimklMutationService internal constructor(
    private val client: SimklApiClient,
    private val onMutationCommitted: () -> Unit = {}
) {
    @Inject
    constructor(client: SimklApiClient, syncRepository: SimklSyncRepository) : this(
        client,
        { syncRepository.refreshAsync(TrackingRefreshIntent.INVALIDATED) }
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    suspend fun moveToList(
        items: Collection<TrackingMediaReference>,
        destination: TrackingListStatus
    ): TrackingMutationResult {
        val candidates = items.validated()
        if (candidates.isEmpty()) return TrackingMutationResult(attemptedCount = 0)
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/add-to-list",
                body = buildSimklListMutationBody(candidates, destination, json),
                retryPolicy = SimklRetryPolicy.SYNC_WRITE
            )
        )
        onMutationCommitted()
        return response.toMutationResult(candidates.size, json)
    }

    suspend fun removeFromList(items: Collection<TrackingMediaReference>): TrackingMutationResult =
        removeFromHistory(items)

    suspend fun addToHistory(items: Collection<TrackingHistoryItem>): TrackingMutationResult {
        val candidates = items.toList().also { historyItems ->
            require(historyItems.all { item -> item.media.hasResolvableIdentity }) {
                "Simkl mutation requires a media ID or title for every item"
            }
        }
        if (candidates.isEmpty()) return TrackingMutationResult(attemptedCount = 0)
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/history",
                body = buildSimklHistoryMutationBody(candidates, json),
                retryPolicy = SimklRetryPolicy.SYNC_WRITE
            )
        )
        onMutationCommitted()
        return response.toMutationResult(candidates.size, json)
    }

    suspend fun removeFromHistory(items: Collection<TrackingMediaReference>): TrackingMutationResult {
        val candidates = items.validated()
        if (candidates.isEmpty()) return TrackingMutationResult(attemptedCount = 0)
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/history/remove",
                body = buildSimklHistoryRemovalBody(candidates, json),
                retryPolicy = SimklRetryPolicy.SYNC_WRITE
            )
        )
        onMutationCommitted()
        return response.toMutationResult(candidates.size, json)
    }

    internal suspend fun scrobble(
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ): SimklScrobbleResult {
        require(event.media.hasResolvableIdentity) { "Simkl scrobble requires a media ID or title" }
        require(event.media.kind == TrackingMediaKind.MOVIE || event.media.episode != null) {
            "Simkl series scrobble requires an episode"
        }
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "simkl mutation request action=${action.wireValue} ${event.scrobbleDiagnosticSummary()}"
        )
        val response = try {
            client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = "/scrobble/${action.wireValue}",
                    body = buildSimklScrobbleBody(event, json),
                    retryPolicy = SimklRetryPolicy.NEVER,
                    scrobbleStopConflictIsSuccess = action == TrackingScrobbleAction.STOP
                )
            )
        } catch (error: Throwable) {
            Log.e(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "simkl mutation failed action=${action.wireValue} " +
                    "error=${error.javaClass.simpleName}:${error.message} ${event.scrobbleDiagnosticSummary()}",
                error
            )
            throw error
        }
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "simkl mutation response action=${action.wireValue} status=${response.status} " +
                "softSuccess=${response.isSoftSuccess} ${event.scrobbleDiagnosticSummary()}"
        )
        return response.toSimklScrobbleResult(action, event, json)
    }

    private fun Collection<TrackingMediaReference>.validated(): List<TrackingMediaReference> =
        toList().also { candidates ->
            require(candidates.all(TrackingMediaReference::hasResolvableIdentity)) {
                "Simkl mutation requires a media ID or title for every item"
            }
        }
}

private fun SimklApiResponse.toMutationResult(
    attemptedCount: Int,
    json: Json
): TrackingMutationResult {
    val payload = body.takeIf(String::isNotBlank)
        ?.let { value -> runCatching { json.parseToJsonElement(value).jsonObject }.getOrNull() }
    val notFound = payload?.get("not_found")
        ?.let { value -> runCatching { value.jsonObject }.getOrNull() }
    val notFoundCount = notFound?.values?.sumOf { value -> (value as? JsonArray)?.size ?: 0 } ?: 0
    val added = payload?.get("added")
        ?.let { value -> runCatching { value.jsonObject }.getOrNull() }
    return TrackingMutationResult(
        attemptedCount = attemptedCount,
        notFoundCount = notFoundCount,
        resolutions = added?.toMutationResolutions().orEmpty()
    )
}

private fun JsonObject.toMutationResolutions(): List<TrackingMutationResolution> {
    val historyResolutions = (get("statuses") as? JsonArray)
        .orEmpty()
        .mapNotNull { element ->
            val response = runCatching { element.jsonObject["response"]?.jsonObject }.getOrNull()
                ?: return@mapNotNull null
            response.toMutationResolution(statusKey = "status")
        }
    if (historyResolutions.isNotEmpty()) return historyResolutions

    return entries.flatMap { (bucket, value) ->
        (value as? JsonArray).orEmpty().mapNotNull { element ->
            runCatching { element.jsonObject }.getOrNull()
                ?.toMutationResolution(statusKey = "to", fallbackKind = bucket.toTrackingMediaKind())
        }
    }
}

private fun JsonObject.toMutationResolution(
    statusKey: String,
    fallbackKind: TrackingMediaKind? = null
): TrackingMutationResolution? {
    val status = TrackingListStatus.fromWireValue(stringValue(statusKey))
    val mediaKind = stringValue("simkl_type")?.toTrackingMediaKind()
        ?: stringValue("type")?.toTrackingMediaKind()
        ?: fallbackKind
    val providerSubtype = stringValue("anime_type")
    if (status == null && mediaKind == null && providerSubtype == null) return null
    return TrackingMutationResolution(status, mediaKind, providerSubtype)
}

private fun JsonObject.stringValue(key: String): String? =
    runCatching { get(key)?.jsonPrimitive?.content }
        .getOrNull()
        ?.trim()
        ?.takeIf(String::isNotEmpty)

private fun String.toTrackingMediaKind(): TrackingMediaKind? = when (lowercase()) {
    "movie", "movies" -> TrackingMediaKind.MOVIE
    "anime" -> TrackingMediaKind.ANIME
    "tv", "show", "shows" -> TrackingMediaKind.SHOW
    else -> null
}

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.ui.SubtitleView
import com.nuvio.tv.domain.model.Subtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * Buffer-preserving sidecar path for external addon subtitles on ExoPlayer.
 *
 * Media3 requires a full [androidx.media3.exoplayer.ExoPlayer.setMediaSource] reload to add
 * [androidx.media3.common.MediaItem.SubtitleConfiguration] tracks mid-playback, which wipes the
 * progressive/VOD buffer. Fast-startup preferred-language auto-select hits that path often.
 *
 * Instead we download + parse with Media3's [DefaultSubtitleParserFactory], then push active
 * [Cue]s to [SubtitleView] while leaving the video MediaPeriod untouched.
 */
private val sidecarParserFactory = DefaultSubtitleParserFactory()
private val mainHandler = Handler(Looper.getMainLooper())

/**
 * Whether [subtitle] can be hot-attached without [androidx.media3.exoplayer.ExoPlayer.setMediaSource].
 *
 * ASS/SSA are excluded when libass is requested/active: those must stay on the media-source path so
 * [io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory] / AssSubtitleView keep full
 * styling. SRT/VTT/TTML still use the buffer-preserving sidecar.
 */
internal fun PlayerRuntimeController.canAttachAddonSubtitleViaSidecar(subtitle: Subtitle): Boolean {
    val mime = PlayerSubtitleUtils.mimeTypeFromUrl(subtitle.url)
    // Preserve pre-sidecar libass behavior for external ASS/SSA addons.
    if (mime == MimeTypes.TEXT_SSA && shouldKeepAssOnLibassMediaPath()) {
        return false
    }
    val format = Format.Builder().setSampleMimeType(mime).build()
    return sidecarParserFactory.supportsFormat(format)
}

/**
 * User wants libass (or the current Exo player already runs the libass pipeline).
 * In that case external ASS/SSA must not be rendered via Media3's basic SsaParser sidecar.
 */
private fun PlayerRuntimeController.shouldKeepAssOnLibassMediaPath(): Boolean {
    if (isUsingMpvEngine()) return false
    return requestedUseLibassByUser || activePlayerUsesLibass
}

internal fun PlayerRuntimeController.isSidecarAddonSubtitleActive(): Boolean {
    return activeSidecarSubtitleKey != null
}

internal fun PlayerRuntimeController.bindExoSubtitleView(subtitleView: SubtitleView?) {
    exoSubtitleViewRef = subtitleView?.let { WeakReference(it) }
    if (subtitleView == null && activeSidecarSubtitleKey != null) {
        // View unbound while sidecar still active; ticker will no-op until rebound.
        return
    }
    if (activeSidecarSubtitleKey != null && sidecarTimedCues.isNotEmpty()) {
        renderSidecarCuesAtCurrentPosition()
    }
}

internal fun PlayerRuntimeController.stopSidecarAddonSubtitle(clearView: Boolean = true) {
    sidecarSubtitleJob?.cancel()
    sidecarSubtitleJob = null
    activeSidecarSubtitleKey = null
    sidecarTimedCues = emptyList()
    lastSidecarCueSignature = null
    if (clearView) {
        postToSubtitleView { it.setCues(emptyList()) }
    }
}

/**
 * Downloads, parses, and starts rendering [subtitle] without reloading the media source.
 * Returns false if the format is unsupported or a newer selection superseded this request.
 */
internal fun PlayerRuntimeController.startSidecarAddonSubtitle(subtitle: Subtitle): Boolean {
    if (!canAttachAddonSubtitleViaSidecar(subtitle)) return false

    val subtitleKey = addonSubtitleKey(subtitle)
    val mime = PlayerSubtitleUtils.mimeTypeFromUrl(subtitle.url)
    val normalizeCuePosition = mime == MimeTypes.TEXT_VTT

    sidecarSubtitleJob?.cancel()
    activeSidecarSubtitleKey = subtitleKey
    lastSidecarCueSignature = null

    // Suppress Exo text tracks so PlayerView does not overwrite our manual cues.
    _exoPlayer?.let { player ->
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    sidecarSubtitleJob = scope.launch {
        try {
            val rawBody = downloadSubtitleBody(subtitle.url)
            val parsed = withContext(Dispatchers.Default) {
                parseSidecarTimedCues(rawBody, mime)
            }
            if (activeSidecarSubtitleKey != subtitleKey) return@launch
            if (parsed.isEmpty()) {
                Log.w(
                    PlayerRuntimeController.TAG,
                    "Sidecar subtitle parse empty for id=${subtitle.id} mime=$mime"
                )
                // Fall back to media-source attach only if still the active selection request.
                if (_uiState.value.selectedAddonSubtitle?.let { addonSubtitleKey(it) } == subtitleKey ||
                    activeSidecarSubtitleKey == subtitleKey
                ) {
                    activeSidecarSubtitleKey = null
                    attachAddonSubtitleViaMediaReload(subtitle)
                }
                return@launch
            }

            sidecarTimedCues = parsed
            Log.d(
                PlayerRuntimeController.TAG,
                "Sidecar subtitle ready id=${subtitle.id} cues=${parsed.size} mime=$mime " +
                    "(buffer preserved)"
            )

            while (isActive && activeSidecarSubtitleKey == subtitleKey) {
                renderSidecarCuesAtCurrentPosition(normalizeCuePosition = normalizeCuePosition)
                delay(SIDECAR_RENDER_INTERVAL_MS)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (activeSidecarSubtitleKey != subtitleKey) return@launch
            Log.w(
                PlayerRuntimeController.TAG,
                "Sidecar subtitle failed id=${subtitle.id}: ${e.message}"
            )
            activeSidecarSubtitleKey = null
            sidecarTimedCues = emptyList()
            // Preserve playback buffer only when the hot path works; on hard failure, fall back.
            attachAddonSubtitleViaMediaReload(subtitle)
        }
    }
    return true
}

internal fun PlayerRuntimeController.renderSidecarCuesAtCurrentPosition(
    normalizeCuePosition: Boolean = false
) {
    val cues = sidecarTimedCues
    if (cues.isEmpty() || activeSidecarSubtitleKey == null) return
    val player = _exoPlayer ?: return
    val positionUs = (
        player.currentPosition.coerceAtLeast(0L) * 1_000L +
            audioDelayUs.get() -
            subtitleDelayUs.get()
        ).coerceAtLeast(0L)
    val active = collectActiveSidecarCues(cues, positionUs)
    val signature = activeCueSignature(active, positionUs)
    if (signature == lastSidecarCueSignature) return
    lastSidecarCueSignature = signature
    val processed = if (normalizeCuePosition) {
        active.map { normalizeSidecarCuePosition(it) }
    } else {
        active
    }
    postToSubtitleView { it.setCues(processed) }
}

private fun PlayerRuntimeController.postToSubtitleView(block: (SubtitleView) -> Unit) {
    val view = exoSubtitleViewRef?.get() ?: return
    if (Looper.myLooper() == Looper.getMainLooper()) {
        block(view)
    } else {
        mainHandler.post {
            exoSubtitleViewRef?.get()?.let(block)
        }
    }
}

private fun parseSidecarTimedCues(rawText: String, mimeType: String): List<CuesWithTiming> {
    val format = Format.Builder().setSampleMimeType(mimeType).build()
    if (!sidecarParserFactory.supportsFormat(format)) return emptyList()
    val parser = sidecarParserFactory.create(format)
    val data = rawText
        .replace("\uFEFF", "")
        .toByteArray(Charsets.UTF_8)
    val out = ArrayList<CuesWithTiming>(256)
    parser.parse(data, SubtitleParser.OutputOptions.allCues()) { cueGroup ->
        if (cueGroup.startTimeUs != C.TIME_UNSET) {
            out.add(cueGroup)
        }
    }
    out.sortBy { it.startTimeUs }
    return out
}

private fun collectActiveSidecarCues(
    cues: List<CuesWithTiming>,
    positionUs: Long
): List<Cue> {
    if (cues.isEmpty()) return emptyList()
    val active = ArrayList<Cue>(4)
    for (entry in cues) {
        val start = entry.startTimeUs
        if (start > positionUs) break
        val end = when {
            entry.endTimeUs != C.TIME_UNSET -> entry.endTimeUs
            entry.durationUs != C.TIME_UNSET -> start + entry.durationUs
            else -> Long.MAX_VALUE
        }
        if (positionUs < end) {
            active.addAll(entry.cues)
        }
    }
    return active
}

private fun activeCueSignature(cues: List<Cue>, positionUs: Long): Long {
    if (cues.isEmpty()) return positionUs xor EMPTY_CUE_SIGNATURE
    var hash = cues.size.toLong()
    for (cue in cues) {
        hash = 31L * hash + (cue.text?.hashCode()?.toLong() ?: 0L)
        hash = 31L * hash + cue.line.toBits().toLong()
        hash = 31L * hash + cue.position.toBits().toLong()
    }
    // Bucket position so we do not thrash setCues every frame when text is stable.
    return hash xor (positionUs / 200_000L)
}

private fun normalizeSidecarCuePosition(cue: Cue): Cue {
    if (cue.bitmap != null || cue.verticalType != Cue.TYPE_UNSET || cue.line == Cue.DIMEN_UNSET) {
        return cue
    }
    return cue.buildUpon()
        .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
        .setLineAnchor(Cue.TYPE_UNSET)
        .build()
}

private const val SIDECAR_RENDER_INTERVAL_MS = 100L
private const val EMPTY_CUE_SIGNATURE = 0x4E5556494FL // "NUVIO"

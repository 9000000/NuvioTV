package com.nuvio.tv.ui.screens.player

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.nuvio.tv.data.local.AudioOutputChannels
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Debounce window so flapping add/remove events coalesce into one route decision. */
private const val AUDIO_ROUTE_CHANGE_DEBOUNCE_MS = 700L

internal enum class BluetoothRoutePlaybackAction {
    NONE,
    UPDATE_SINK_IN_PLACE,
}

/**
 * Bluetooth connect/disconnect must never rebuild ExoPlayer: that restarts video from a
 * seek point and shows the loading overlay. Update the PCM/passthrough sink policy in place.
 */
internal fun decideBluetoothRoutePlaybackAction(
    wasBluetooth: Boolean,
    isBluetooth: Boolean,
    usingMpv: Boolean
): BluetoothRoutePlaybackAction {
    if (usingMpv || wasBluetooth == isBluetooth) {
        return BluetoothRoutePlaybackAction.NONE
    }
    return BluetoothRoutePlaybackAction.UPDATE_SINK_IN_PLACE
}

internal suspend fun PlayerRuntimeController.applyStoredAudioDelayForCurrentRouteIfEnabled() {
    if (!rememberAudioDelayPerDeviceEnabled) return

    val route = AudioOutputRouteDetector.detect(context) ?: return
    currentAudioOutputRoute = route

    val storedDelayMs = audioDelayRouteDataStore.loadDelayMs(route.key) ?: 0
    applyAudioDelay(storedDelayMs, persistForCurrentRoute = false)
    Log.d(
        PlayerRuntimeController.TAG,
        "Applied audio delay ${storedDelayMs}ms for route=${route.key}"
    )
}

internal fun PlayerRuntimeController.persistAudioDelayForCurrentRoute(delayMs: Int) {
    if (!rememberAudioDelayPerDeviceEnabled) return

    val route = AudioOutputRouteDetector.detect(context) ?: currentAudioOutputRoute ?: return
    currentAudioOutputRoute = route
    scope.launch {
        audioDelayRouteDataStore.saveDelayMs(route.key, delayMs)
    }
}

internal fun PlayerRuntimeController.registerAudioDelayRouteCallback() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || audioOutputRouteCallback != null) return

    val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return
    val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            onAudioOutputRouteMaybeChanged("added", addedDevices)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            onAudioOutputRouteMaybeChanged("removed", removedDevices)
        }
    }

    runCatching {
        audioManager.registerAudioDeviceCallback(callback, null)
        audioOutputRouteCallback = callback
        Log.d(PlayerRuntimeController.TAG, "Registered audio output route callback")
    }.onFailure {
        Log.w(PlayerRuntimeController.TAG, "Failed to register audio route callback", it)
    }
}

internal fun PlayerRuntimeController.unregisterAudioDelayRouteCallback() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val callback = audioOutputRouteCallback ?: return
    val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return
    runCatching {
        audioManager.unregisterAudioDeviceCallback(callback)
    }.onFailure {
        Log.w(PlayerRuntimeController.TAG, "Failed to unregister audio route callback", it)
    }
    audioOutputRouteCallback = null
}

private fun PlayerRuntimeController.onAudioOutputRouteMaybeChanged(
    reason: String,
    devices: Array<out AudioDeviceInfo>
) {
    if (isReleasingPlayer) return
    if (_exoPlayer == null && !isUsingMpvEngine()) return

    Log.d(
        PlayerRuntimeController.TAG,
        "Audio device $reason (count=${devices.size}); scheduling route re-probe"
    )

    audioRouteChangeJob?.cancel()
    audioRouteChangeJob = scope.launch {
        delay(AUDIO_ROUTE_CHANGE_DEBOUNCE_MS)
        if (isReleasingPlayer) return@launch

        val oldRoute = currentAudioOutputRoute
        val newRoute = AudioOutputRouteDetector.detect(context)
        if (newRoute != null) {
            currentAudioOutputRoute = newRoute
        }

        if (rememberAudioDelayPerDeviceEnabled) {
            applyStoredAudioDelayForCurrentRouteIfEnabled()
        }

        val wasBluetooth = oldRoute?.isBluetooth == true
        val isBluetooth = (newRoute ?: currentAudioOutputRoute)?.isBluetooth == true
        when (
            decideBluetoothRoutePlaybackAction(
                wasBluetooth = wasBluetooth,
                isBluetooth = isBluetooth,
                usingMpv = isUsingMpvEngine()
            )
        ) {
            BluetoothRoutePlaybackAction.NONE -> {
                Log.d(
                    PlayerRuntimeController.TAG,
                    "Audio route after device $reason: bluetooth=$isBluetooth (was=$wasBluetooth); player stays running"
                )
            }
            BluetoothRoutePlaybackAction.UPDATE_SINK_IN_PLACE -> {
                if (_exoPlayer == null) return@launch
                Log.i(
                    PlayerRuntimeController.TAG,
                    "Bluetooth media route changed $wasBluetooth → $isBluetooth after device $reason; " +
                        "updating PCM policy in place (player stays running)"
                )
                applyBluetoothAudioRouteInPlace(isBluetooth)
            }
        }
    }
}

internal fun PlayerRuntimeController.applyBluetoothAudioRouteInPlace(isBluetooth: Boolean) {
    val sink = playbackSpeedAwareAudioSink
    if (sink != null && sink.isBluetoothForcePcm() == isBluetooth) {
        Log.d(
            PlayerRuntimeController.TAG,
            "Bluetooth PCM policy already $isBluetooth; leaving player running"
        )
        return
    }

    sink?.setBluetoothForcePcm(isBluetooth)

    val settings = currentPlayerSettingsForReport
    val forceOptical = !isBluetooth &&
        settings.forceOpticalPassthrough &&
        settings.decoderPriority != 0
    val downmixEnabled = settings.effectiveDownmixEnabled || isBluetooth
    val outputChannels = if (isBluetooth) {
        AudioOutputChannels.CHANNELS_2_0
    } else {
        settings.audioOutputChannels
    }
    ffmpegAudioRenderer?.setForceOpticalPassthrough(forceOptical)
    if (downmixEnabled) {
        ffmpegAudioRenderer?.setAudioOutputChannels(
            outputChannels.ffmpegLayoutName,
            outputChannels.channelCount
        )
        ffmpegAudioRenderer?.setDownmixNormalizationEnabled(!settings.maintainOriginalAudioOnDownmix)
    } else {
        ffmpegAudioRenderer?.setAudioOutputChannels(null, 0)
        ffmpegAudioRenderer?.setDownmixNormalizationEnabled(false)
    }

    sink?.notifyAudioProcessingRequirementChanged()
    _exoPlayer?.let { player ->
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().build()
    }
}

@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.data.repository.TraktProgressService
import com.nuvio.tv.data.simkl.SimklConnectionMode
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

@Composable
internal fun TraktAccountDialog(
    state: TraktUiState,
    onStartConnection: () -> Unit,
    onRetryPolling: () -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val logo = painterResource(R.drawable.trakt_logo_wordmark)
    NuvioDialog(
        onDismiss = onDismiss,
        title = "",
        width = 720.dp,
        titleTextAlign = TextAlign.Center,
        suppressFirstKeyUp = false
    ) {
        if (state.mode == TraktConnectionMode.CONNECTED) {
            ConnectedTrackingAccountContent(
                logo = logo,
                logoContentDescription = stringResource(R.string.cd_trakt_logo),
                username = state.username ?: stringResource(R.string.trakt_user_fallback),
                statusMessage = state.statusMessage,
                errorMessage = state.errorMessage,
                isLoading = state.isLoading,
                onSync = onSync,
                onDisconnect = onDisconnect,
                onDismiss = onDismiss,
                tokenRefreshText = state.tokenExpiresAtMillis?.let { expiresAt ->
                    stringResource(
                        R.string.trakt_token_refreshes,
                        formatTrackingDuration((expiresAt - System.currentTimeMillis()).coerceAtLeast(0L))
                    )
                },
                stats = state.connectedStats,
                isStatsLoading = state.isStatsLoading
            )
        } else {
            val displayUrl = state.verificationUrl ?: TRAKT_ACTIVATION_URL
            val qrUrl = state.deviceUserCode?.let { "$TRAKT_ACTIVATION_URL/$it" } ?: displayUrl
            TrackingDeviceAuthContent(
                providerName = stringResource(R.string.trakt_name),
                logo = logo,
                logoContentDescription = stringResource(R.string.cd_trakt_logo),
                qrContentDescription = stringResource(R.string.cd_trakt_qr),
                instruction = stringResource(R.string.trakt_awaiting_instruction),
                userCode = state.deviceUserCode,
                displayUrl = displayUrl,
                qrUrl = qrUrl,
                expiresAtEpochMs = state.deviceCodeExpiresAtMillis,
                isLoading = state.isLoading,
                isPolling = state.isPolling,
                credentialsConfigured = state.credentialsConfigured,
                statusMessage = state.statusMessage,
                errorMessage = state.errorMessage,
                missingCredentialsMessage = stringResource(R.string.trakt_missing_credentials),
                onStartConnection = onStartConnection,
                onRetryPolling = onRetryPolling,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
internal fun SimklAccountDialog(
    state: SimklSettingsUiState,
    onStartConnection: () -> Unit,
    onRetryPolling: () -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val logo = rememberRawSvgPainter(R.raw.simkl_tv_wordmark, 220.dp)
    NuvioDialog(
        onDismiss = onDismiss,
        title = "",
        width = 720.dp,
        titleTextAlign = TextAlign.Center,
        suppressFirstKeyUp = false
    ) {
        if (state.mode == SimklConnectionMode.CONNECTED) {
            ConnectedTrackingAccountContent(
                logo = logo,
                logoContentDescription = stringResource(R.string.cd_simkl_logo),
                username = state.username ?: stringResource(R.string.simkl_user_fallback),
                statusMessage = state.statusMessage,
                errorMessage = state.errorMessage,
                isLoading = state.isLoading,
                onSync = onSync,
                onDisconnect = onDisconnect,
                onDismiss = onDismiss,
                onVisit = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SIMKL_WEBSITE_URL)))
                    }
                }
            )
        } else {
            TrackingDeviceAuthContent(
                providerName = stringResource(R.string.simkl_name),
                logo = logo,
                logoContentDescription = stringResource(R.string.cd_simkl_logo),
                qrContentDescription = stringResource(R.string.cd_simkl_qr),
                instruction = stringResource(R.string.simkl_awaiting_instruction),
                userCode = state.userCode,
                displayUrl = state.verificationUri,
                qrUrl = state.verificationUri,
                expiresAtEpochMs = state.expiresAtEpochMs,
                isLoading = state.isLoading,
                isPolling = state.isPolling,
                credentialsConfigured = state.credentialsConfigured,
                statusMessage = state.statusMessage,
                errorMessage = state.errorMessage,
                missingCredentialsMessage = stringResource(R.string.simkl_missing_credentials),
                onStartConnection = onStartConnection,
                onRetryPolling = onRetryPolling,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun TrackingDeviceAuthContent(
    providerName: String,
    logo: Painter,
    logoContentDescription: String,
    qrContentDescription: String,
    instruction: String,
    userCode: String?,
    displayUrl: String?,
    qrUrl: String?,
    expiresAtEpochMs: Long?,
    isLoading: Boolean,
    isPolling: Boolean,
    credentialsConfigured: Boolean,
    statusMessage: String?,
    errorMessage: String?,
    missingCredentialsMessage: String,
    onStartConnection: () -> Unit,
    onRetryPolling: () -> Unit,
    onDismiss: () -> Unit
) {
    TrackingProviderWordmark(logo, logoContentDescription)
    when {
        isLoading -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    NuvioTheme.spacing.md,
                    Alignment.CenterHorizontally
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LoadingIndicator(modifier = Modifier.size(28.dp))
                Text(
                    text = stringResource(R.string.tracking_connecting_provider, providerName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        }
        !userCode.isNullOrBlank() && !qrUrl.isNullOrBlank() -> {
            val qrBitmap = remember(qrUrl) {
                runCatching { QrCodeGenerator.generate(qrUrl, 420, margin = 1) }.getOrNull()
            }
            val nowMillis by produceState(
                initialValue = System.currentTimeMillis(),
                key1 = expiresAtEpochMs
            ) {
                while (true) {
                    value = System.currentTimeMillis()
                    delay(1_000L)
                }
            }
            Text(
                text = instruction,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            if (qrBitmap != null) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = qrContentDescription,
                        modifier = Modifier.size(144.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
            ) {
                Text(
                    text = userCode,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = NuvioTheme.colors.TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!displayUrl.isNullOrBlank()) {
                    Text(
                        text = displayUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                expiresAtEpochMs?.let { expiresAt ->
                    Text(
                        text = stringResource(
                            R.string.trakt_code_expires,
                            formatTrackingDuration((expiresAt - nowMillis).coerceAtLeast(0L))
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        else -> {
            Text(
                text = errorMessage ?: if (credentialsConfigured) {
                    stringResource(R.string.tracking_status_disconnected)
                } else {
                    missingCredentialsMessage
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (errorMessage != null || !credentialsConfigured) {
                    NuvioTheme.colors.Error
                } else {
                    NuvioTheme.colors.TextSecondary
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    val visibleStatus = errorMessage ?: statusMessage
    if (!visibleStatus.isNullOrBlank() && (!userCode.isNullOrBlank() || isLoading)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                NuvioTheme.spacing.sm,
                Alignment.CenterHorizontally
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isPolling && errorMessage == null) {
                LoadingIndicator(modifier = Modifier.size(18.dp))
            }
            Text(
                text = visibleStatus,
                style = MaterialTheme.typography.bodySmall,
                color = if (errorMessage == null) {
                    NuvioTheme.colors.TextSecondary
                } else {
                    NuvioTheme.colors.Error
                },
                textAlign = TextAlign.Center
            )
        }
    }

    SettingsDialogActionRow(horizontalAlignment = Alignment.CenterHorizontally) {
        SettingsDialogActionButton(
            text = stringResource(R.string.action_cancel),
            onClick = onDismiss
        )
        if (!isLoading && userCode.isNullOrBlank()) {
            SettingsDialogActionButton(
                text = stringResource(R.string.action_retry),
                onClick = onStartConnection,
                primary = true,
                enabled = credentialsConfigured
            )
        } else if (!isLoading && !isPolling && errorMessage != null && !userCode.isNullOrBlank()) {
            SettingsDialogActionButton(
                text = stringResource(R.string.action_retry),
                onClick = onRetryPolling,
                primary = true
            )
        }
    }
}

@Composable
private fun ConnectedTrackingAccountContent(
    logo: Painter,
    logoContentDescription: String,
    username: String,
    statusMessage: String?,
    errorMessage: String?,
    isLoading: Boolean,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
    tokenRefreshText: String? = null,
    stats: TraktProgressService.TraktCachedStats? = null,
    isStatsLoading: Boolean = false,
    onVisit: (() -> Unit)? = null
) {
    TrackingProviderWordmark(logo, logoContentDescription)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        TrackingConnectionBadge()
        Text(
            text = username,
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary,
            textAlign = TextAlign.Center
        )
        if (!tokenRefreshText.isNullOrBlank()) {
            Text(
                text = tokenRefreshText,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary,
                textAlign = TextAlign.Center
            )
        }
    }
    if (stats != null || isStatsLoading) {
        TraktConnectedStatsStrip(stats, isStatsLoading)
    }
    val visibleStatus = errorMessage ?: statusMessage
    if (!visibleStatus.isNullOrBlank()) {
        Text(
            text = visibleStatus,
            style = MaterialTheme.typography.bodySmall,
            color = if (errorMessage == null) {
                NuvioTheme.colors.TextSecondary
            } else {
                NuvioTheme.colors.Error
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
    SettingsDialogActionRow(horizontalAlignment = Alignment.CenterHorizontally) {
        SettingsDialogActionButton(
            text = stringResource(R.string.action_close),
            onClick = onDismiss
        )
        if (onVisit != null) {
            SettingsDialogActionButton(
                text = stringResource(R.string.simkl_visit),
                onClick = onVisit
            )
        }
        SettingsDialogActionButton(
            text = stringResource(R.string.trakt_disconnect),
            onClick = onDisconnect,
            enabled = !isLoading
        )
        SettingsDialogActionButton(
            text = stringResource(R.string.simkl_sync_now),
            onClick = onSync,
            primary = true,
            enabled = !isLoading
        )
    }
}

@Composable
private fun TrackingProviderWordmark(
    logo: Painter,
    contentDescription: String
) {
    Image(
        painter = logo,
        contentDescription = contentDescription,
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
        contentScale = ContentScale.Fit
    )
}

@Composable
private fun TrackingConnectionBadge() {
    Row(
        modifier = Modifier
            .background(
                NuvioTheme.colors.Success.copy(alpha = 0.12f),
                RoundedCornerShape(999.dp)
            )
            .padding(horizontal = NuvioTheme.spacing.md, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(NuvioTheme.colors.Success, RoundedCornerShape(999.dp))
        )
        Text(
            text = stringResource(R.string.tracking_status_connected),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = NuvioTheme.colors.Success
        )
    }
}

@Composable
private fun TraktConnectedStatsStrip(
    stats: TraktProgressService.TraktCachedStats?,
    isLoading: Boolean
) {
    val values = if (isLoading) {
        listOf("…", "…", "…", "…")
    } else {
        listOf(
            stats?.moviesWatched?.toString() ?: "-",
            stats?.showsWatched?.toString() ?: "-",
            stats?.episodesWatched?.toString() ?: "-",
            stats?.totalWatchedHours?.let { "${it}h" } ?: "-"
        )
    }
    val labels = listOf(
        stringResource(R.string.trakt_stat_movies),
        stringResource(R.string.trakt_stat_shows),
        stringResource(R.string.trakt_stat_episodes),
        stringResource(R.string.trakt_stat_watched_hours)
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        Text(
            text = stringResource(R.string.trakt_cached_label),
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextTertiary
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(NuvioTheme.spacing.hairline)
                .background(NuvioTheme.colors.Border)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            values.forEachIndexed { index, value ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
                    Text(
                        text = labels[index],
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
                if (index != values.lastIndex) {
                    Box(
                        modifier = Modifier
                            .width(NuvioTheme.spacing.hairline)
                            .height(44.dp)
                            .background(NuvioTheme.colors.Border)
                    )
                }
            }
        }
    }
}

internal fun formatTrackingDuration(valueMs: Long): String {
    val totalSeconds = (valueMs / 1000L).coerceAtLeast(0L)
    val days = TimeUnit.SECONDS.toDays(totalSeconds)
    val hours = TimeUnit.SECONDS.toHours(totalSeconds) % 24
    val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60
    val seconds = totalSeconds % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

private const val TRAKT_ACTIVATION_URL = "https://trakt.tv/activate"
private const val SIMKL_WEBSITE_URL = "https://simkl.com"

// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Confirmation and determinate progress dialogs for DSS2 Sky Background and HQ Object Image downloads.
 */
@Composable
fun HiPSDownloadDialogs(viewModel: MainViewModel) {
    if (viewModel.showHiPSDownloadConfirm) {
        val hasPartial = viewModel.skyStorageBytes > 0L
        val bodyText = buildString {
            append(
                stringResource(
                    Res.string.hips_confirm_body,
                    Healpix.MAX_ORDER,
                    HiPSDownloadManager.formatCount(Healpix.TOTAL_TILES),
                    HiPSDownloadManager.estimatedDownloadMb()
                )
            )
            if (hasPartial) {
                append("\n\n")
                append(
                    stringResource(
                        Res.string.hips_confirm_resume_note,
                        HiPSDownloadManager.formatMbValue(viewModel.skyStorageBytes)
                    )
                )
            }
        }
        OliveAlertDialog(
            title = stringResource(Res.string.hips_confirm_title),
            text = bodyText,
            confirmButtonText = if (hasPartial) {
                stringResource(Res.string.hips_action_resume)
            } else {
                stringResource(Res.string.hips_action_download)
            },
            onConfirm = { viewModel.startDss2Download() },
            dismissButtonText = stringResource(Res.string.hips_action_cancel),
            onDismiss = { viewModel.showHiPSDownloadConfirm = false }
        )
    }

    if (viewModel.showHiPSDownloadProgress) {
        val state = viewModel.hipsDownloadState
        if (state !is HiPSDownloadState.Idle) {
            val isRunningOrFailed =
                state is HiPSDownloadState.Downloading || state is HiPSDownloadState.Failed
            val isFailed = state is HiPSDownloadState.Failed
            val (done, total, statusText) = when (state) {
                is HiPSDownloadState.Downloading -> Triple(
                    state.completedTiles,
                    state.totalTiles,
                    stringResource(
                        Res.string.hips_progress_tile_status,
                        state.currentOrder,
                        HiPSDownloadManager.formatCount(state.completedTiles),
                        HiPSDownloadManager.formatCount(state.totalTiles),
                        HiPSDownloadManager.formatMbValue(state.downloadedBytes)
                    )
                )

                is HiPSDownloadState.Packing -> Triple(
                    state.packedTiles,
                    state.totalTiles,
                    stringResource(
                        Res.string.hips_progress_packing,
                        HiPSDownloadManager.formatCount(state.packedTiles),
                        HiPSDownloadManager.formatCount(state.totalTiles)
                    )
                )

                is HiPSDownloadState.Failed -> Triple(
                    state.completedTiles,
                    state.totalTiles,
                    hipsFailureMessage(state.reason)
                )

                HiPSDownloadState.Idle -> Triple(0, 1, "")
            }
            HiPSProgressPopup(
                title = stringResource(Res.string.hips_progress_title),
                done = done,
                total = total,
                statusText = statusText,
                isFailed = isFailed,
                canCancel = isRunningOrFailed,
                onDismiss = {
                    if (isFailed) viewModel.cancelDss2Download()
                    else viewModel.showHiPSDownloadProgress = false
                },
                onCancel = { viewModel.cancelDss2Download() },
                onRetry = { viewModel.startDss2Download() },
                onHide = { viewModel.showHiPSDownloadProgress = false }
            )
        }
    }

    val pendingHq = viewModel.pendingHqCatalogConfirm
    if (pendingHq != null) {
        val estMb = HiPSDownloadManager.formatMbValue(
            HiPSDownloadManager.estimatedTilesBytes(pendingHq.missingKeys)
        )
        OliveAlertDialog(
            title = stringResource(Res.string.hips_hq_confirm_title, pendingHq.categoryTitle),
            text = stringResource(
                Res.string.hips_hq_confirm_body,
                pendingHq.categoryTitle,
                HiPSDownloadManager.formatCount(pendingHq.missingKeys.size),
                estMb
            ),
            confirmButtonText = stringResource(Res.string.hips_action_download),
            onConfirm = { viewModel.confirmCatalogHqDownload() },
            dismissButtonText = stringResource(Res.string.hips_action_cancel),
            onDismiss = { viewModel.pendingHqCatalogConfirm = null }
        )
    }

    if (viewModel.showHqDownloadProgress) {
        when (val hqState = viewModel.hqDownloadState) {
            HiPSHqDownloadState.Idle -> {}
            is HiPSHqDownloadState.Downloading,
            is HiPSHqDownloadState.Failed -> {
                val isFailed = hqState is HiPSHqDownloadState.Failed
                val catLabel = when (hqState) {
                    is HiPSHqDownloadState.Downloading -> hqState.catalogTitle
                    is HiPSHqDownloadState.Failed -> hqState.catalogTitle
                    HiPSHqDownloadState.Idle -> null
                }
                val progressTitle = if (catLabel != null) {
                    stringResource(Res.string.hips_hq_progress_title, catLabel)
                } else {
                    stringResource(Res.string.label_hq_object_images)
                }

                val (done, total, statusText) = when (hqState) {
                    is HiPSHqDownloadState.Downloading -> Triple(
                        hqState.completedTiles,
                        hqState.totalTiles,
                        stringResource(
                            Res.string.hips_hq_progress_status,
                            HiPSDownloadManager.formatCount(hqState.completedTiles),
                            HiPSDownloadManager.formatCount(hqState.totalTiles),
                            HiPSDownloadManager.formatMbValue(hqState.downloadedBytes)
                        )
                    )

                    is HiPSHqDownloadState.Failed -> Triple(
                        hqState.completedTiles,
                        hqState.totalTiles,
                        hipsFailureMessage(hqState.reason)
                    )

                    HiPSHqDownloadState.Idle -> Triple(0, 1, "")
                }
                HiPSProgressPopup(
                    title = progressTitle,
                    done = done,
                    total = total,
                    statusText = statusText,
                    isFailed = isFailed,
                    canCancel = true,
                    onDismiss = {
                        if (isFailed) viewModel.cancelHqDownload()
                        else viewModel.showHqDownloadProgress = false
                    },
                    onCancel = { viewModel.cancelHqDownload() },
                    onRetry = { viewModel.retryFailedHqDownload() },
                    onHide = { viewModel.showHqDownloadProgress = false }
                )
            }
        }
    }

    if (viewModel.showRemoveHqConfirm) {
        OliveAlertDialog(
            title = stringResource(Res.string.hips_hq_remove_confirm_title),
            text = stringResource(
                Res.string.hips_hq_remove_confirm_body,
                HiPSDownloadManager.formatMbValue(viewModel.hqStorageBytes)
            ),
            confirmButtonText = stringResource(Res.string.hips_action_remove),
            onConfirm = { viewModel.removeHqImages() },
            dismissButtonText = stringResource(Res.string.hips_action_cancel),
            onDismiss = { viewModel.showRemoveHqConfirm = false }
        )
    }
}

@Composable
internal fun hipsFailureMessage(reason: HiPSFailureReason): String = when (reason) {
    is HiPSFailureReason.TileDownloadFailed ->
        stringResource(Res.string.hips_error_tile_download, reason.missingCount)

    HiPSFailureReason.PackAssemblyFailed ->
        stringResource(Res.string.hips_error_pack_assembly)

    HiPSFailureReason.StorageWriteFailed ->
        stringResource(Res.string.hips_error_storage_write)

    HiPSFailureReason.StorageBackendUnavailable ->
        stringResource(Res.string.hips_error_storage_unavailable)
}

@Composable
private fun HiPSProgressPopup(
    title: String,
    done: Int,
    total: Int,
    statusText: String,
    isFailed: Boolean,
    canCancel: Boolean,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onHide: () -> Unit
) {
    InfoPopup(
        title = title,
        onDismiss = onDismiss,
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (canCancel) {
                    TextButton(onClick = onCancel) {
                        Text(text = stringResource(Res.string.hips_action_cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                if (isFailed) {
                    Button(onClick = onRetry) {
                        Text(text = stringResource(Res.string.hips_action_retry))
                    }
                } else {
                    Button(onClick = onHide) {
                        Text(text = stringResource(Res.string.hips_action_hide))
                    }
                }
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val fraction = (done.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isFailed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onBackground
                }
            )
        }
    }
}

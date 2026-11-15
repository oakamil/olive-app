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
 * Confirmation and determinate progress dialogs for the on-demand DSS2 Color HiPS download.
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
            InfoPopup(
                title = stringResource(Res.string.hips_progress_title),
                onDismiss = {
                    if (state is HiPSDownloadState.Failed) {
                        viewModel.cancelDss2Download()
                    } else {
                        viewModel.showHiPSDownloadProgress = false
                    }
                },
                bottomBar = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (state is HiPSDownloadState.Downloading || state is HiPSDownloadState.Failed) {
                            TextButton(onClick = { viewModel.cancelDss2Download() }) {
                                Text(text = stringResource(Res.string.hips_action_cancel))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        if (state is HiPSDownloadState.Failed) {
                            Button(onClick = { viewModel.startDss2Download() }) {
                                Text(text = stringResource(Res.string.hips_action_retry))
                            }
                        } else {
                            Button(onClick = { viewModel.showHiPSDownloadProgress = false }) {
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
                    val (done, total, statusText) = when (state) {
                        is HiPSDownloadState.Downloading -> Triple(
                            state.completedTiles,
                            state.totalTiles,
                            stringResource(
                                Res.string.hips_progress_tile_status,
                                state.currentOrder,
                                HiPSDownloadManager.formatCount(state.completedTiles),
                                HiPSDownloadManager.formatCount(state.totalTiles),
                                HiPSDownloadManager.formatMbValue(state.downloadedBytes),
                                HiPSDownloadManager.estimatedDownloadMb()
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

                        is HiPSDownloadState.Failed -> {
                            val errorText = when (val reason = state.reason) {
                                is HiPSFailureReason.TileDownloadFailed -> stringResource(
                                    Res.string.hips_error_tile_download,
                                    reason.missingCount
                                )

                                HiPSFailureReason.PackAssemblyFailed -> stringResource(
                                    Res.string.hips_error_pack_assembly
                                )

                                HiPSFailureReason.StorageBackendUnavailable -> stringResource(
                                    Res.string.hips_error_storage_unavailable
                                )
                            }
                            Triple(state.completedTiles, state.totalTiles, errorText)
                        }

                        HiPSDownloadState.Idle -> return@InfoPopup
                    }
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
                        color = if (state is HiPSDownloadState.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onBackground
                        }
                    )
                }
            }
        }
    }
}

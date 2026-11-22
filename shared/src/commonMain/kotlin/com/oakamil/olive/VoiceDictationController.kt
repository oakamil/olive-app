// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.runtime.Composable

interface VoiceDictationController {
    val isSupported: Boolean
    val isListening: Boolean
    val permissionDeniedPermanently: Boolean

    fun toggleListening(onTranscriptUpdate: (sessionText: String, isFinal: Boolean) -> Unit)
    fun stopListening()
    fun dismissPermissionDenied()
    fun openAppSettings()
}

fun appendDictationText(base: String, spoken: String): String =
    if (base.isBlank()) {
        spoken
    } else {
        val sep = if (base.last().isWhitespace()) "" else " "
        "$base$sep$spoken"
    }

@Composable
fun VoicePermissionDeniedDialog(voiceController: VoiceDictationController) {
    if (voiceController.permissionDeniedPermanently) {
        OliveAlertDialog(
            title = stringResource(Res.string.title_mic_permission_required),
            text = stringResource(Res.string.desc_mic_permission_required),
            confirmButtonText = stringResource(Res.string.btn_open_settings),
            onConfirm = { voiceController.openAppSettings() },
            dismissButtonText = stringResource(Res.string.btn_cancel),
            onDismiss = { voiceController.dismissPermissionDenied() }
        )
    }
}

@Composable
expect fun rememberVoiceDictationController(): VoiceDictationController

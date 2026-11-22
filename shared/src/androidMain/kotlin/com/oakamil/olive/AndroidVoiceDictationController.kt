// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private class AndroidVoiceDictationController(
    private val context: Context,
    private val onRequestAudioPermission: () -> Unit
) : VoiceDictationController {
    override val isSupported: Boolean = SpeechRecognizer.isRecognitionAvailable(context)
    override var isListening by mutableStateOf(false)
        private set
    override var permissionDeniedPermanently by mutableStateOf(false)
        private set

    private var recognizer: SpeechRecognizer? = null
    private var activeCallback: ((String, Boolean) -> Unit)? = null
    private var pendingCallbackAfterPermission: ((String, Boolean) -> Unit)? = null
    private var sessionToken: Int = 0
    private var disposed: Boolean = false

    fun onPermissionResult(granted: Boolean) {
        val pending = pendingCallbackAfterPermission
        pendingCallbackAfterPermission = null
        if (disposed) return
        if (granted) {
            permissionDeniedPermanently = false
            if (pending != null) {
                startInternal(pending)
            }
        } else {
            val activity = context.findActivity()
            val showRationale = activity != null &&
                    ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.RECORD_AUDIO
                    )
            permissionDeniedPermanently = !showRationale
        }
    }

    override fun toggleListening(onTranscriptUpdate: (sessionText: String, isFinal: Boolean) -> Unit) {
        if (disposed) return
        if (isListening) {
            isListening = false
            try {
                recognizer?.stopListening()
            } catch (_: Exception) {
                stopListening()
            }
            return
        }
        if (!isSupported) return

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            pendingCallbackAfterPermission = onTranscriptUpdate
            onRequestAudioPermission()
            return
        }
        startInternal(onTranscriptUpdate)
    }

    private fun startInternal(onTranscriptUpdate: (String, Boolean) -> Unit) {
        if (disposed) return
        val token = ++sessionToken
        activeCallback = onTranscriptUpdate
        val sr = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { created ->
            recognizer = created
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (token == sessionToken && !disposed) {
                    isListening = true
                }
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                if (token != sessionToken) return
                isListening = false
                activeCallback = null
            }

            override fun onResults(results: Bundle?) {
                if (token != sessionToken) return
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                val cb = activeCallback
                isListening = false
                activeCallback = null
                if (text.isNotBlank()) {
                    cb?.invoke(text, true)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (token != sessionToken) return
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) {
                    activeCallback?.invoke(text, false)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            isListening = true
            sr.startListening(intent)
        } catch (e: Exception) {
            Log.e("AndroidVoiceDictation", "Failed to start speech recognizer: ${e.message}")
            if (token == sessionToken) {
                isListening = false
                activeCallback = null
            }
        }
    }

    override fun stopListening() {
        sessionToken++
        isListening = false
        activeCallback = null
        pendingCallbackAfterPermission = null
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
    }

    override fun dismissPermissionDenied() {
        permissionDeniedPermanently = false
    }

    override fun openAppSettings() {
        permissionDeniedPermanently = false
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun release() {
        disposed = true
        sessionToken++
        isListening = false
        activeCallback = null
        pendingCallbackAfterPermission = null
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }
}

@Composable
actual fun rememberVoiceDictationController(): VoiceDictationController {
    val context = LocalContext.current
    var controllerRef: AndroidVoiceDictationController? = null
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        controllerRef?.onPermissionResult(granted)
    }
    val controller = remember(context) {
        AndroidVoiceDictationController(
            context = context,
            onRequestAudioPermission = {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        )
    }
    controllerRef = controller
    DisposableEffect(controller) {
        onDispose {
            controller.release()
        }
    }
    return controller
}

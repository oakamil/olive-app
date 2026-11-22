// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.setActive
import platform.Foundation.NSLocale
import platform.Foundation.NSURL
import platform.Foundation.currentLocale
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@OptIn(ExperimentalForeignApi::class)
private class IosVoiceDictationController : VoiceDictationController {
    private val speechRecognizer: SFSpeechRecognizer? =
        runCatching { SFSpeechRecognizer(locale = NSLocale.currentLocale) }.getOrNull()
    private val audioEngine = AVAudioEngine()
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest? = null
    private var recognitionTask: SFSpeechRecognitionTask? = null
    private var tapInstalled = false
    private var activeCallback: ((String, Boolean) -> Unit)? = null
    private var sessionToken: Int = 0
    private var disposed: Boolean = false

    override val isSupported: Boolean
        get() = speechRecognizer != null

    override var isListening by mutableStateOf(false)
        private set
    override var permissionDeniedPermanently by mutableStateOf(false)
        private set

    override fun toggleListening(onTranscriptUpdate: (sessionText: String, isFinal: Boolean) -> Unit) {
        if (disposed) return
        if (isListening) {
            stopListening()
            return
        }
        val recognizer = speechRecognizer ?: return
        val token = ++sessionToken

        SFSpeechRecognizer.requestAuthorization { authStatus ->
            dispatch_async(dispatch_get_main_queue()) {
                if (disposed || token != sessionToken) return@dispatch_async
                if (authStatus != SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized) {
                    permissionDeniedPermanently = true
                    return@dispatch_async
                }
                AVAudioSession.sharedInstance().requestRecordPermission { micGranted ->
                    dispatch_async(dispatch_get_main_queue()) {
                        if (disposed || token != sessionToken) return@dispatch_async
                        if (!micGranted) {
                            permissionDeniedPermanently = true
                            return@dispatch_async
                        }
                        permissionDeniedPermanently = false
                        startRecognitionOnMain(recognizer, onTranscriptUpdate, token)
                    }
                }
            }
        }
    }

    private fun startRecognitionOnMain(
        recognizer: SFSpeechRecognizer,
        onTranscriptUpdate: (String, Boolean) -> Unit,
        token: Int
    ) {
        cleanupSession()
        if (disposed || token != sessionToken || !recognizer.isAvailable()) return

        val audioSession = AVAudioSession.sharedInstance()
        val categoryOk = audioSession.setCategory(
            AVAudioSessionCategoryRecord,
            mode = AVAudioSessionModeMeasurement,
            options = AVAudioSessionCategoryOptionDuckOthers,
            error = null
        )
        val activeOk = audioSession.setActive(true, error = null)
        if (!categoryOk || !activeOk) return

        val inputNode = audioEngine.inputNode
        val recordingFormat = inputNode.outputFormatForBus(0u)
        if (recordingFormat.sampleRate <= 0.0 || recordingFormat.channelCount == 0u) {
            audioSession.setActive(false, error = null)
            return
        }

        val request = SFSpeechAudioBufferRecognitionRequest().apply {
            shouldReportPartialResults = true
        }
        recognitionRequest = request
        activeCallback = onTranscriptUpdate

        inputNode.installTapOnBus(
            bus = 0u,
            bufferSize = 1024u,
            format = recordingFormat
        ) { buffer, _ ->
            if (buffer != null) {
                request.appendAudioPCMBuffer(buffer)
            }
        }
        tapInstalled = true

        audioEngine.prepare()
        if (!audioEngine.startAndReturnError(null)) {
            cleanupSession()
            return
        }

        isListening = true
        recognitionTask = recognizer.recognitionTaskWithRequest(request) { result, error ->
            dispatch_async(dispatch_get_main_queue()) {
                if (disposed || token != sessionToken) return@dispatch_async
                val transcript = result?.bestTranscription?.formattedString
                val isFinal = result?.isFinal() == true
                if (!transcript.isNullOrBlank()) {
                    activeCallback?.invoke(transcript, isFinal)
                }
                if (error != null || isFinal) {
                    stopListening()
                }
            }
        }
    }

    private fun cleanupSession() {
        isListening = false
        activeCallback = null
        if (audioEngine.isRunning()) {
            audioEngine.stop()
        }
        if (tapInstalled) {
            audioEngine.inputNode.removeTapOnBus(0u)
            tapInstalled = false
        }
        recognitionRequest?.endAudio()
        recognitionRequest = null
        recognitionTask?.cancel()
        recognitionTask = null
        AVAudioSession.sharedInstance().setActive(false, error = null)
    }

    override fun stopListening() {
        sessionToken++
        cleanupSession()
    }

    fun dispose() {
        disposed = true
        stopListening()
    }

    override fun dismissPermissionDenied() {
        permissionDeniedPermanently = false
    }

    override fun openAppSettings() {
        permissionDeniedPermanently = false
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString)
        if (url != null) {
            UIApplication.sharedApplication.openURL(
                url = url,
                options = emptyMap<Any?, Any?>(),
                completionHandler = null
            )
        }
    }
}

@Composable
actual fun rememberVoiceDictationController(): VoiceDictationController {
    val controller = remember { IosVoiceDictationController() }
    DisposableEffect(controller) {
        onDispose {
            controller.dispose()
        }
    }
    return controller
}

package com.oakamil.olive

import androidx.compose.runtime.Composable

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    settingsManager: SettingsManager,
    isCameraConnected: Boolean,
    activeCameraSelection: String,
    isStopping: Boolean,
    isStarting: Boolean,
    detectedIpAddress: String,
    onCameraSelectionChanged: (String) -> Unit,
    onNavigateToTuner: () -> Unit,
    onEnterPipMode: () -> Unit,
    onToggleEngine: () -> Unit,
    onRefreshLocation: () -> Unit = {}
) {
    if (viewModel.isEngineRunning) {
        EngineRunningScreen(
            viewModel = viewModel,
            settingsManager = settingsManager,
            detectedIpAddress = detectedIpAddress,
            activeCameraSelection = activeCameraSelection,
            isStopping = isStopping,
            isStarting = isStarting,
            onNavigateToTuner = onNavigateToTuner,
            onEnterPipMode = onEnterPipMode,
            onToggleEngine = onToggleEngine
        )
    } else {
        StartScreen(
            viewModel = viewModel,
            settingsManager = settingsManager,
            isCameraConnected = isCameraConnected,
            activeCameraSelection = activeCameraSelection,
            isStopping = isStopping,
            isStarting = isStarting,
            onCameraSelectionChanged = onCameraSelectionChanged,
            onToggleEngine = onToggleEngine,
            onRefreshLocation = onRefreshLocation
        )
    }
}

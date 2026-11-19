package com.oakamil.olive

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import androidx.annotation.Keep
import com.jiangdg.usb.USBMonitor
import com.jiangdg.uvc.UVCCamera
import java.util.concurrent.atomic.AtomicBoolean

class UvcCameraWrapper() : UvcCameraHandler {
    var uvcCameraInstance: UVCCamera? = null
    override var previewWidth: Int = 640
        private set
    override var previewHeight: Int = 480
        private set

    private var currentFps: Int = 30

    private val pipelineReady = AtomicBoolean(false)
    override val isPipelineReady: Boolean get() = pipelineReady.get()

    @Volatile
    var isAutoExposureActive: Boolean = false
        private set

    private var dummySurfaceTexture: SurfaceTexture? = null
    private var dummySurface: Surface? = null

    private fun getNativePtr(): Long {
        val cam = uvcCameraInstance ?: return 0L
        return try {
            val field = UVCCamera::class.java.getDeclaredField("mNativePtr")
            field.isAccessible = true
            field.getLong(cam)
        } catch (_: Exception) {
            0L
        }
    }

    fun initialize(
        ctrlBlock: USBMonitor.UsbControlBlock,
        targetWidth: Int = 1920,
        targetHeight: Int = 1080,
        fps: Int = 30,
        targetFormat: String = "MJPEG"
    ) {
        currentFps = fps.coerceAtLeast(1)
        uvcCameraInstance = UVCCamera()
        try {
            uvcCameraInstance?.open(ctrlBlock)

            val isMjpeg = targetFormat.equals("MJPEG", ignoreCase = true)
            val requestedFormat =
                if (isMjpeg) UVCCamera.FRAME_FORMAT_MJPEG else UVCCamera.FRAME_FORMAT_YUYV
            val fallbackFormat =
                if (isMjpeg) UVCCamera.FRAME_FORMAT_YUYV else UVCCamera.FRAME_FORMAT_MJPEG

            var initialized = false

            // 1. Try exact requested format, resolution, and fps
            try {
                uvcCameraInstance?.setPreviewSize(
                    targetWidth,
                    targetHeight,
                    fps,
                    fps,
                    requestedFormat,
                    1.0f
                )
                previewWidth = targetWidth
                previewHeight = targetHeight
                initialized = true
                Log.i(
                    "UvcCameraWrapper",
                    "Successfully matched $targetFormat ${targetWidth}x${targetHeight} @ ${fps}fps"
                )
            } catch (_: Exception) {
                // Try min_fps 1 to max_fps fps
                try {
                    uvcCameraInstance?.setPreviewSize(
                        targetWidth,
                        targetHeight,
                        1,
                        fps,
                        requestedFormat,
                        1.0f
                    )
                    previewWidth = targetWidth
                    previewHeight = targetHeight
                    initialized = true
                    Log.i(
                        "UvcCameraWrapper",
                        "Successfully matched $targetFormat ${targetWidth}x${targetHeight} @ 1-${fps}fps"
                    )
                } catch (_: Exception) {
                }
            }

            // 2. If requested format/size failed, scan supported sizes
            val supportedSizes =
                uvcCameraInstance?.supportedSizeList?.sortedByDescending { it.width * it.height }

            if (!initialized && supportedSizes != null) {
                for (size in supportedSizes) {
                    try {
                        uvcCameraInstance?.setPreviewSize(
                            size.width,
                            size.height,
                            fps,
                            fps,
                            requestedFormat,
                            1.0f
                        )
                        previewWidth = size.width
                        previewHeight = size.height
                        initialized = true
                        Log.i(
                            "UvcCameraWrapper",
                            "Matched $targetFormat ${size.width}x${size.height} @ ${fps}fps"
                        )
                        break
                    } catch (_: Exception) {
                        try {
                            uvcCameraInstance?.setPreviewSize(
                                size.width,
                                size.height,
                                fps,
                                fps,
                                fallbackFormat,
                                1.0f
                            )
                            previewWidth = size.width
                            previewHeight = size.height
                            initialized = true
                            Log.i(
                                "UvcCameraWrapper",
                                "Matched fallback format ${size.width}x${size.height} @ ${fps}fps"
                            )
                            break
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            if (!initialized && supportedSizes != null && supportedSizes.isNotEmpty()) {
                for (fallbackSize in supportedSizes) {
                    try {
                        uvcCameraInstance?.setPreviewSize(
                            fallbackSize.width,
                            fallbackSize.height,
                            1,
                            30,
                            UVCCamera.FRAME_FORMAT_MJPEG,
                            1.0f
                        )
                        previewWidth = fallbackSize.width
                        previewHeight = fallbackSize.height
                        break
                    } catch (_: Exception) {
                        try {
                            uvcCameraInstance?.setPreviewSize(
                                fallbackSize.width,
                                fallbackSize.height,
                                1,
                                30,
                                UVCCamera.FRAME_FORMAT_YUYV,
                                1.0f
                            )
                            previewWidth = fallbackSize.width
                            previewHeight = fallbackSize.height
                            break
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            dummySurfaceTexture = SurfaceTexture(0)
            dummySurface = Surface(dummySurfaceTexture)
        } catch (e: Exception) {
            Log.e("UvcCameraWrapper", "Initialization error", e)
        }
    }

    @Volatile
    private var isPreviewRunning = false
    private val cameraLock = Any()

    @Volatile
    private var primeThread: Thread? = null

    override fun startZeroCopyStream(initialGain: Int) {
        pipelineReady.set(false)
        primeThread?.interrupt()
        synchronized(cameraLock) {
            uvcCameraInstance?.setFrameCallback({ frame ->
                if (frame != null && frame.isDirect && pipelineReady.get()) {
                    OliveEngine.pushExternalUvcFrame(
                        frame,
                        previewWidth,
                        previewHeight,
                        System.currentTimeMillis()
                    )
                }
            }, UVCCamera.PIXEL_FORMAT_NV21)
            uvcCameraInstance?.setPreviewDisplay(dummySurface)
            uvcCameraInstance?.startPreview()
            // Immediately detach the dummy preview surface after startPreview() spawns preview_thread_func.
            // UVCCamera.startPreview() requires mPreviewWindow != NULL only at thread creation time.
            // Setting mPreviewWindow = NULL skips uvc_any2rgbx + copyToSurface in UVCPreview::do_preview,
            // preventing the copyToSurface 8-line loop-unrolling buffer over-read crash on 960x540 while
            // still delivering all frames to setFrameCallback via the capture queue.
            uvcCameraInstance?.setPreviewDisplay(null as Surface?)
            isPreviewRunning = true
        }

        // Configure camera for astronomy in Auto-Exposure (Mode 2) with Target Gain
        primeThread = Thread {
            try {
                // Sleep 200ms for USB endpoint stabilization
                Thread.sleep(200)

                synchronized(cameraLock) {
                    val cam = uvcCameraInstance
                    val nativePtr = getNativePtr()
                    if (!isPreviewRunning || cam == null || nativePtr == 0L) return@Thread

                    // Ensure native control & processing unit support bitmasks are initialized
                    cam.updateCameraParams()

                    val getAEMode = UVCCamera::class.java.getDeclaredMethod(
                        "nativeGetExposureMode",
                        Long::class.java
                    ).apply { isAccessible = true }
                    val setAEMode = UVCCamera::class.java.getDeclaredMethod(
                        "nativeSetExposureMode",
                        Long::class.java,
                        Int::class.java
                    ).apply { isAccessible = true }
                    val getExp = UVCCamera::class.java.getDeclaredMethod(
                        "nativeGetExposure",
                        Long::class.java
                    ).apply { isAccessible = true }

                    // Query and log initial hardware state
                    try {
                        val initMode = getAEMode.invoke(null, nativePtr) as Int
                        val initExp = getExp.invoke(null, nativePtr) as Int
                        Log.i(
                            "UvcCameraWrapper",
                            "Initial UVC state: AE Mode=$initMode, Exposure=$initExp (100us units)"
                        )
                    } catch (e: Exception) {
                        Log.w("UvcCameraWrapper", "Failed to query initial UVC state: ${e.message}")
                    }

                    // 1. Primary: Set AE Mode = 8 (Aperture Priority: Auto Exposure with Fixed/Manual Iris)
                    var aeConfigured = false
                    try {
                        val res8 = setAEMode.invoke(null, nativePtr, 8) as Int
                        val activeMode = getAEMode.invoke(null, nativePtr) as Int
                        Log.i(
                            "UvcCameraWrapper",
                            "Configured UVC AE Mode 8 (res=$res8, activeMode=$activeMode)"
                        )
                        if (res8 == 0 && (activeMode == 8 || activeMode == 2)) {
                            aeConfigured = true
                        }
                    } catch (e: Exception) {
                        Log.w("UvcCameraWrapper", "Failed to set AE Mode 8: ${e.message}")
                    }

                    // 2. Generic Fallback: If Mode 8 was rejected, configure manual shutter (Mode 4 or Mode 1)
                    if (!aeConfigured) {
                        try {
                            val currentMode = getAEMode.invoke(null, nativePtr) as Int
                            var manualModeConfigured = false

                            // If camera is already in Mode 4 (Shutter Priority) or accepts Mode 4, use Mode 4.
                            // This avoids sending Mode 1 to fixed-iris cameras that reject it with UVC_ERROR_PIPE (-9).
                            if (currentMode == 4) {
                                manualModeConfigured = true
                                Log.i(
                                    "UvcCameraWrapper",
                                    "Camera is already in Mode 4 (Shutter Priority)"
                                )
                            } else {
                                val res4 = setAEMode.invoke(null, nativePtr, 4) as Int
                                Log.i("UvcCameraWrapper", "Configured UVC AE Mode 4 (res=$res4)")
                                if (res4 == 0) {
                                    manualModeConfigured = true
                                }
                            }

                            // Only try Mode 1 if Mode 4 was rejected
                            if (!manualModeConfigured) {
                                val res1 = setAEMode.invoke(null, nativePtr, 1) as Int
                                Log.i("UvcCameraWrapper", "Fallback UVC AE Mode 1 result: $res1")
                            }

                            val updateExpLimit = UVCCamera::class.java.getDeclaredMethod(
                                "nativeUpdateExposureLimit",
                                Long::class.java
                            ).apply { isAccessible = true }
                            updateExpLimit.invoke(cam, nativePtr)

                            val minExpField = UVCCamera::class.java.getDeclaredField("mExposureMin")
                                .apply { isAccessible = true }
                            val maxExpField = UVCCamera::class.java.getDeclaredField("mExposureMax")
                                .apply { isAccessible = true }
                            val minExp = minExpField.getInt(cam)
                            val maxExp = maxExpField.getInt(cam)

                            val rawTargetExp =
                                (10_000 / currentFps.coerceAtLeast(1)).coerceAtLeast(1)
                            val targetExp = if (maxExp > 0 && maxExp >= minExp) {
                                rawTargetExp.coerceIn(minExp, maxExp)
                            } else {
                                rawTargetExp
                            }

                            val setExp = UVCCamera::class.java.getDeclaredMethod(
                                "nativeSetExposure",
                                Long::class.java,
                                Int::class.java
                            ).apply { isAccessible = true }
                            val expRes = setExp.invoke(null, nativePtr, targetExp) as Int
                            Log.i(
                                "UvcCameraWrapper",
                                "Manual exposure configured: targetExp=$targetExp (range $minExp..$maxExp, res=$expRes)"
                            )
                        } catch (e: Exception) {
                            Log.w(
                                "UvcCameraWrapper",
                                "Failed manual exposure fallback: ${e.message}"
                            )
                        }
                    }

                    // Apply maximum gain to Processing Unit (100%) for both Mode 8 and Mode 4/1
                    val targetGainPct = initialGain.coerceIn(0, 100)
                    setGain(targetGainPct)
                    Log.i("UvcCameraWrapper", "Applied UVC gain: ${targetGainPct}%")

                    isAutoExposureActive = aeConfigured

                    try {
                        val finalExp = getExp.invoke(null, nativePtr) as Int
                        Log.i(
                            "UvcCameraWrapper",
                            "Hardware exposure after config: $finalExp (100us units)"
                        )
                    } catch (_: Exception) {
                    }
                }

                // Settle for 300ms so ISP AEC loop meters scene and ramps up
                Thread.sleep(300)
            } catch (_: InterruptedException) {
                Log.i("UvcCameraWrapper", "AE configuration interrupted")
            } catch (e: Exception) {
                Log.e("UvcCameraWrapper", "AE configuration error: ${e.message}", e)
            } finally {
                if (isPreviewRunning && Thread.currentThread() == primeThread) {
                    pipelineReady.set(true)
                    Log.i("UvcCameraWrapper", "UVC astro pipeline ready (Auto-Exposure active)")
                }
            }
        }.also { it.start() }
    }

    // Used from native (JNI) and Kotlin
    // Note: UVCCamera.setGain(int) expects a 0..100 percentage and internally scales to mGainMin..mGainMax
    @Keep
    fun setGain(gain: Int) {
        synchronized(cameraLock) {
            val cam = uvcCameraInstance ?: return
            try {
                cam.updateCameraParams()
                val pct = gain.coerceIn(0, 100)
                cam.gain = pct
                Log.i("UvcCameraWrapper", "Set UVC gain percentage: $pct%")
            } catch (e: Exception) {
                Log.e("UvcCameraWrapper", "Failed to set gain: ${e.message}")
            }
        }
    }

    // Used from native
    @Keep
    fun setBrightness(brightness: Int) {
        synchronized(cameraLock) {
            uvcCameraInstance?.updateCameraParams()
            uvcCameraInstance?.brightness = brightness
        }
    }

    // Used from native
    @Keep
    fun getBrightness(): Int {
        return getHardwareBrightness() ?: 50
    }

    // Used from native (JNI) to query if camera is running pure Auto-Exposure (Mode 8 or 2)
    @Keep
    @Suppress("unused")
    fun isAutoExposure(): Boolean = isAutoExposureActive

    override fun release() {
        pipelineReady.set(false)
        isPreviewRunning = false
        primeThread?.interrupt()
        primeThread = null
        synchronized(cameraLock) {
            try {
                uvcCameraInstance?.stopPreview()
            } catch (e: Exception) {
                Log.w("UvcCameraWrapper", "Error stopping preview", e)
            }
            try {
                uvcCameraInstance?.destroy()
            } catch (e: Exception) {
                Log.w("UvcCameraWrapper", "Error destroying camera", e)
            }
            uvcCameraInstance = null
        }

        dummySurface?.release()
        dummySurface = null
        dummySurfaceTexture?.release()
        dummySurfaceTexture = null
    }

    override fun getHardwareGain(): Int? {
        synchronized(cameraLock) {
            if (uvcCameraInstance == null) return null
            return try {
                uvcCameraInstance?.updateCameraParams()
                uvcCameraInstance?.gain
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun getHardwareBrightness(): Int? {
        synchronized(cameraLock) {
            if (uvcCameraInstance == null) return null
            return try {
                uvcCameraInstance?.updateCameraParams()
                uvcCameraInstance?.brightness
            } catch (_: Exception) {
                null
            }
        }
    }
}

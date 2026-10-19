package com.oakamil.olive

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import androidx.annotation.Keep
import com.jiangdg.usb.USBMonitor
import com.jiangdg.uvc.UVCCamera

class UvcCameraWrapper() : UvcCameraHandler {
    var uvcCameraInstance: UVCCamera? = null
    override var previewWidth: Int = 640
        private set
    override var previewHeight: Int = 480
        private set

    private var dummySurfaceTexture: SurfaceTexture? = null
    private var dummySurface: Surface? = null

    fun initialize(ctrlBlock: USBMonitor.UsbControlBlock, fps: Int) {
        uvcCameraInstance = UVCCamera()
        try {
            uvcCameraInstance?.open(ctrlBlock)

            val supportedSizes =
                uvcCameraInstance?.supportedSizeList?.sortedByDescending { it.width * it.height }
            var initialized = false

            if (supportedSizes != null) {
                for (size in supportedSizes) {
                    try {
                        uvcCameraInstance?.setPreviewSize(
                            size.width,
                            size.height,
                            fps,
                            fps,
                            UVCCamera.FRAME_FORMAT_MJPEG,
                            1.0f
                        )
                        previewWidth = size.width
                        previewHeight = size.height
                        initialized = true
                        Log.i(
                            "UvcCameraWrapper",
                            "Successfully matched MJPEG ${size.width}x${size.height} @ ${fps}fps"
                        )
                        break
                    } catch (_: Exception) {
                        try {
                            uvcCameraInstance?.setPreviewSize(
                                size.width,
                                size.height,
                                fps,
                                fps,
                                UVCCamera.FRAME_FORMAT_YUYV,
                                1.0f
                            )
                            previewWidth = size.width
                            previewHeight = size.height
                            initialized = true
                            Log.i(
                                "UvcCameraWrapper",
                                "Successfully matched YUYV ${size.width}x${size.height} @ ${fps}fps"
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
            dummySurfaceTexture?.setDefaultBufferSize(previewWidth, previewHeight)
            dummySurface = Surface(dummySurfaceTexture)
            uvcCameraInstance?.setPreviewDisplay(dummySurface)
        } catch (e: Exception) {
            Log.e("UvcCameraWrapper", "Initialization error", e)
        }
    }

    private var isPreviewRunning = false

    override fun startZeroCopyStream() {
        uvcCameraInstance?.setFrameCallback({ frame ->
            if (frame != null && frame.isDirect) {
                OliveEngine.pushExternalUvcFrame(
                    frame,
                    previewWidth,
                    previewHeight,
                    System.currentTimeMillis()
                )
            }
        }, UVCCamera.PIXEL_FORMAT_NV21)
        uvcCameraInstance?.startPreview()
        isPreviewRunning = true

        // Configure camera for astronomy after stream starts
        try {
            // 1. Disable AWB via public API
            uvcCameraInstance?.autoWhiteBlance = false

            // 2. Force Manual / Shutter Priority AE via reflection
            val ptrField = UVCCamera::class.java.getDeclaredField("mNativePtr")
            ptrField.isAccessible = true
            val nativePtr = ptrField.getLong(uvcCameraInstance)

            if (nativePtr != 0L) {
                val setAEMode = UVCCamera::class.java.getDeclaredMethod(
                    "nativeSetExposureMode",
                    Long::class.java,
                    Int::class.java
                )
                setAEMode.isAccessible = true
                val res = setAEMode.invoke(null, nativePtr, 1) as Int
                if (res != 0) {
                    Log.w(
                        "UvcCameraWrapper",
                        "Manual AE not supported, falling back to Shutter Priority (4)"
                    )
                    setAEMode.invoke(null, nativePtr, 4)
                }
            }
        } catch (e: Exception) {
            Log.e("UvcCameraWrapper", "Failed to configure AE/AWB: ${e.message}")
        }
    }

    // Used from native
    @Keep
    fun setGain(gain: Int) {
        uvcCameraInstance?.updateCameraParams()
        uvcCameraInstance?.gain = gain
    }

    // Used from native
    @Keep
    fun setBrightness(brightness: Int) {
        uvcCameraInstance?.updateCameraParams()
        uvcCameraInstance?.brightness = brightness
    }

    // Used from native
    @Keep
    fun getBrightness(): Int {
        return getHardwareBrightness() ?: 50
    }

    override fun release() {
        if (isPreviewRunning) {
            try {
                uvcCameraInstance?.stopPreview()
            } catch (e: Exception) {
                Log.w("UvcCameraWrapper", "Error stopping preview", e)
            }
            isPreviewRunning = false
        }
        try {
            uvcCameraInstance?.destroy()
        } catch (e: Exception) {
            Log.w("UvcCameraWrapper", "Error destroying camera", e)
        }
        uvcCameraInstance = null

        dummySurface?.release()
        dummySurface = null
        dummySurfaceTexture?.release()
        dummySurfaceTexture = null
    }

    override fun getHardwareGain(): Int? {
        if (uvcCameraInstance == null) return null
        return try {
            uvcCameraInstance?.updateCameraParams()
            uvcCameraInstance?.gain
        } catch (_: Exception) {
            null
        }
    }

    override fun getHardwareBrightness(): Int? {
        if (uvcCameraInstance == null) return null
        return try {
            uvcCameraInstance?.updateCameraParams()
            uvcCameraInstance?.brightness
        } catch (_: Exception) {
            null
        }
    }
}

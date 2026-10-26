// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

interface UvcCameraHandler {
    val previewWidth: Int
    val previewHeight: Int
    val isPipelineReady: Boolean get() = true
    fun startZeroCopyStream(initialGain: Int = 100)
    fun getHardwareBrightness(): Int?
    fun getHardwareGain(): Int?
    fun release()
}

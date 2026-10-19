// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

interface UvcCameraHandler {
    val previewWidth: Int
    val previewHeight: Int
    fun startZeroCopyStream()
    fun getHardwareBrightness(): Int?
    fun getHardwareGain(): Int?
    fun release()
}

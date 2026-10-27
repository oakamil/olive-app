package com.oakamil.olive

data class UvcFormat(
    val width: Int,
    val height: Int,
    val fps: Int,
    val format: String // "MJPEG" or "YUV2"
) {
    val label: String get() = "${width}x${height} $format @ $fps fps"

    fun fitsUsb2Bandwidth(): Boolean {
        if (format.equals("MJPEG", ignoreCase = true)) {
            return true // Compressed MJPEG easily fits USB 2.0 (under 10 MB/s)
        }
        // Uncompressed YUV2 / YUYV (16 bits = 2 bytes per pixel)
        val bytesPerSec = width.toLong() * height.toLong() * 2L * fps.toLong()
        // Max USB 2.0 high-bandwidth isochronous endpoint throughput (3 * 1024 * 8000)
        return bytesPerSec <= 24_576_000L
    }

    companion object {
        // Predefined fallback list with default FPS: 30, 20, 10, 5
        val FALLBACK_FORMATS = listOf(
            UvcFormat(1920, 1080, 30, "MJPEG"),
            UvcFormat(1920, 1080, 20, "MJPEG"),
            UvcFormat(1920, 1080, 10, "MJPEG"),
            UvcFormat(1920, 1080, 5, "YUV2")
        )

        fun isUsb3Descriptors(raw: ByteArray?): Boolean {
            if (raw == null || raw.size < 4) return false
            var offset = 0
            while (offset + 2 <= raw.size) {
                val len = raw[offset].toInt() and 0xFF
                if (len < 2 || offset + len > raw.size) break
                val descType = raw[offset + 1].toInt() and 0xFF
                // SuperSpeed Endpoint Companion Descriptor (0x30)
                if (descType == 0x30) return true
                offset += len
            }
            return false
        }

        fun filterFormats(all: List<UvcFormat>, isUsb3: Boolean = false): List<UvcFormat> {
            if (all.isEmpty()) return FALLBACK_FORMATS
            val native = all.maxByOrNull { it.width.toLong() * it.height.toLong() }
                ?: return FALLBACK_FORMATS
            val maxWidth = native.width
            val maxHeight = native.height
            val binnedWidth = maxWidth / 2
            val binnedHeight = maxHeight / 2

            val valid = all.filter { fmt ->
                val is1x = (fmt.width == maxWidth && fmt.height == maxHeight)
                // Allow ±8 px rounding tolerance for macroblock-16 alignment (e.g. 960x544 for 1080p binned)
                val is2x2Binned = (kotlin.math.abs(fmt.width - binnedWidth) <= 8 &&
                        kotlin.math.abs(fmt.height - binnedHeight) <= 8)
                val matchesRes = is1x || is2x2Binned
                val matchesFormat = fmt.format.equals("MJPEG", ignoreCase = true) ||
                        fmt.format.startsWith("YUV", ignoreCase = true) ||
                        fmt.format.startsWith("YUY", ignoreCase = true)
                val validFps = fmt.fps in 1..30
                val fitsBandwidth = if (isUsb3) true else fmt.fitsUsb2Bandwidth()
                matchesRes && matchesFormat && validFps && fitsBandwidth
            }

            if (valid.isEmpty()) return FALLBACK_FORMATS

            // Deduplicate per FPS: pick the single best format for each unique FPS
            // (highest resolution, preferring uncompressed YUV2 over MJPEG if resolution matches)
            val uniqueFps = valid.map { it.fps }.distinct().sortedDescending()
            return uniqueFps.map { fps ->
                val candidates = valid.filter { it.fps == fps }
                candidates.maxWithOrNull(
                    compareBy<UvcFormat> { it.width * it.height }
                        .thenBy {
                            if (it.format.startsWith(
                                    "YUV",
                                    ignoreCase = true
                                ) || it.format.startsWith("YUY", ignoreCase = true)
                            ) 1 else 0
                        }
                )!!
            }
        }

        fun findBestFormatForFps(targetFps: Int, formats: List<UvcFormat>): UvcFormat {
            val validList = formats.ifEmpty { FALLBACK_FORMATS }
            val availableFps = validList.map { it.fps }.distinct().sortedDescending()
            val resolvedFps = availableFps.firstOrNull { it <= targetFps }
                ?: availableFps.lastOrNull()
                ?: targetFps

            val candidates = validList.filter { it.fps == resolvedFps }
            return candidates.maxWithOrNull(
                compareBy<UvcFormat> { it.width * it.height }
                    .thenBy {
                        if (it.format.startsWith(
                                "YUV",
                                ignoreCase = true
                            ) || it.format.startsWith("YUY", ignoreCase = true)
                        ) 1 else 0
                    }
            ) ?: validList.first()
        }

        fun parseRawDescriptors(raw: ByteArray?, isUsb3Override: Boolean? = null): List<UvcFormat> {
            if (raw == null || raw.size < 4) return emptyList()
            val formats = mutableListOf<UvcFormat>()
            var offset = 0
            var currentFormatName = "YUV2"

            while (offset + 2 <= raw.size) {
                val len = raw[offset].toInt() and 0xFF
                if (len < 2 || offset + len > raw.size) break
                val descType = raw[offset + 1].toInt() and 0xFF

                // CS_INTERFACE descriptor = 0x24
                if (descType == 0x24 && len >= 3) {
                    val subType = raw[offset + 2].toInt() and 0xFF
                    when (subType) {
                        0x04 -> currentFormatName = "YUV2" // VS_FORMAT_UNCOMPRESSED
                        0x06 -> currentFormatName = "MJPEG" // VS_FORMAT_MJPEG
                        0x05, 0x07 -> {
                            // VS_FRAME_UNCOMPRESSED (0x05) or VS_FRAME_MJPEG (0x07)
                            if (len >= 26) {
                                val width = (raw[offset + 5].toInt() and 0xFF) or
                                        ((raw[offset + 6].toInt() and 0xFF) shl 8)
                                val height = (raw[offset + 7].toInt() and 0xFF) or
                                        ((raw[offset + 8].toInt() and 0xFF) shl 8)
                                val frameIntervalType = raw[offset + 25].toInt() and 0xFF

                                if (frameIntervalType > 0) {
                                    for (i in 0 until frameIntervalType) {
                                        val intOffset = offset + 26 + i * 4
                                        if (intOffset + 4 <= offset + len) {
                                            val interval = (raw[intOffset].toLong() and 0xFF) or
                                                    ((raw[intOffset + 1].toLong() and 0xFF) shl 8) or
                                                    ((raw[intOffset + 2].toLong() and 0xFF) shl 16) or
                                                    ((raw[intOffset + 3].toLong() and 0xFF) shl 24)
                                            if (interval > 0) {
                                                val fps = kotlin.math.round(10_000_000.0 / interval)
                                                    .toInt()
                                                if (fps in 1..120) {
                                                    formats.add(
                                                        UvcFormat(
                                                            width,
                                                            height,
                                                            fps,
                                                            currentFormatName
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else if (len >= 38) {
                                    // Continuous frame intervals: min, max, step
                                    val minInt = (raw[offset + 26].toLong() and 0xFF) or
                                            ((raw[offset + 27].toLong() and 0xFF) shl 8) or
                                            ((raw[offset + 28].toLong() and 0xFF) shl 16) or
                                            ((raw[offset + 29].toLong() and 0xFF) shl 24)
                                    val maxInt = (raw[offset + 30].toLong() and 0xFF) or
                                            ((raw[offset + 31].toLong() and 0xFF) shl 8) or
                                            ((raw[offset + 32].toLong() and 0xFF) shl 16) or
                                            ((raw[offset + 33].toLong() and 0xFF) shl 24)
                                    if (minInt in 1..maxInt) {
                                        val maxFps =
                                            kotlin.math.round(10_000_000.0 / minInt).toInt()
                                        val minFps =
                                            kotlin.math.round(10_000_000.0 / maxInt).toInt()
                                        for (standardFps in listOf(30, 25, 20, 15, 10, 5, 2)) {
                                            if (standardFps in minFps..maxFps) {
                                                formats.add(
                                                    UvcFormat(
                                                        width,
                                                        height,
                                                        standardFps,
                                                        currentFormatName
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                offset += len
            }
            val isUsb3 = isUsb3Override ?: isUsb3Descriptors(raw)
            return filterFormats(formats, isUsb3 = isUsb3)
        }
    }
}

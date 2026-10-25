package com.oakamil.olive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UvcFormatTest {

    @Test
    fun testUsb2BandwidthCalculation() {
        // MJPEG is compressed, always fits
        val mjpeg1080p30 = UvcFormat(1920, 1080, 30, "MJPEG")
        assertTrue(mjpeg1080p30.fitsUsb2Bandwidth())

        // YUV2 at 1920x1080 @ 5 fps: 1920 * 1080 * 2 * 5 = 20,736,000 bytes/s <= 24,576,000 bytes/s -> Fits
        val yuv1080p5 = UvcFormat(1920, 1080, 5, "YUV2")
        assertTrue(yuv1080p5.fitsUsb2Bandwidth())

        // YUV2 at 1920x1080 @ 10 fps: 1920 * 1080 * 2 * 10 = 41,472,000 bytes/s > 24,576,000 bytes/s -> Exceeds
        val yuv1080p10 = UvcFormat(1920, 1080, 10, "YUV2")
        assertFalse(yuv1080p10.fitsUsb2Bandwidth())

        // YUV2 at 960x540 @ 20 fps: 960 * 540 * 2 * 20 = 20,736,000 bytes/s <= 24,576,000 bytes/s -> Fits
        val yuv540p20 = UvcFormat(960, 540, 20, "YUV2")
        assertTrue(yuv540p20.fitsUsb2Bandwidth())

        // YUV2 at 960x540 @ 25 fps: 960 * 540 * 2 * 25 = 25,920,000 bytes/s > 24,576,000 bytes/s -> Exceeds
        val yuv540p25 = UvcFormat(960, 540, 25, "YUV2")
        assertFalse(yuv540p25.fitsUsb2Bandwidth())

        // YUV2 at 960x540 @ 30 fps: 960 * 540 * 2 * 30 = 31,104,000 bytes/s > 24,576,000 bytes/s -> Exceeds
        val yuv540p30 = UvcFormat(960, 540, 30, "YUV2")
        assertFalse(yuv540p30.fitsUsb2Bandwidth())
    }

    @Test
    fun testFallbackFormatsAreStrictlyUsb2Compliant() {
        val fallbackFps = UvcFormat.FALLBACK_FORMATS.map { it.fps }
        assertEquals(listOf(30, 20, 10, 5), fallbackFps)
        for (format in UvcFormat.FALLBACK_FORMATS) {
            assertTrue("Fallback format $format must fit USB 2.0 bandwidth", format.fitsUsb2Bandwidth())
        }
    }

    @Test
    fun testFilterFormatsOrderingAndFiltering() {
        val inputList = listOf(
            UvcFormat(1920, 1080, 15, "MJPEG"),
            UvcFormat(1920, 1080, 30, "MJPEG"),
            UvcFormat(1920, 1080, 60, "MJPEG"), // Should be excluded (> 30fps)
            UvcFormat(1280, 720, 30, "MJPEG"),  // Should be excluded (not 1080p)
            UvcFormat(960, 540, 30, "YUV2"),    // Should be excluded (exceeds bandwidth)
            UvcFormat(960, 540, 20, "YUV2"),    // Included (20fps)
            UvcFormat(960, 540, 15, "YUV2"),    // At 15fps, 1080p MJPEG beats 540p YUV2
            UvcFormat(1920, 1080, 5, "YUV2"),   // Included (5fps)
            UvcFormat(1920, 1080, 5, "MJPEG"),  // At 5fps, 1080p YUV2 beats 1080p MJPEG
            UvcFormat(960, 540, 5, "YUV2"),     // At 5fps, 1080p YUV2 beats 540p YUV2
            UvcFormat(1920, 1080, 10, "YUV2")   // Should be excluded (exceeds bandwidth)
        )

        val filtered = UvcFormat.filterFormats(inputList)

        // Deduplicated per unique FPS descending: 30, 20, 15, 5
        assertEquals(4, filtered.size)
        assertEquals(UvcFormat(1920, 1080, 30, "MJPEG"), filtered[0])
        assertEquals(UvcFormat(960, 540, 20, "YUV2"), filtered[1])
        assertEquals(UvcFormat(1920, 1080, 15, "MJPEG"), filtered[2])
        assertEquals(UvcFormat(1920, 1080, 5, "YUV2"), filtered[3])
    }

    @Test
    fun testFindBestFormatForFps() {
        val formats = listOf(
            UvcFormat(1920, 1080, 30, "MJPEG"),
            UvcFormat(1920, 1080, 20, "MJPEG"),
            UvcFormat(960, 540, 15, "YUV2"),
            UvcFormat(1920, 1080, 10, "MJPEG"),
            UvcFormat(1920, 1080, 5, "YUV2")
        )

        // Exact match
        assertEquals(UvcFormat(1920, 1080, 30, "MJPEG"), UvcFormat.findBestFormatForFps(30, formats))
        assertEquals(UvcFormat(960, 540, 15, "YUV2"), UvcFormat.findBestFormatForFps(15, formats))

        // Next fastest matching FPS when target is not available
        // Target 25 fps falls back to 20 fps
        assertEquals(UvcFormat(1920, 1080, 20, "MJPEG"), UvcFormat.findBestFormatForFps(25, formats))
        // Target 12 fps falls back to 10 fps
        assertEquals(UvcFormat(1920, 1080, 10, "MJPEG"), UvcFormat.findBestFormatForFps(12, formats))
        // Target 2 fps (below minimum 5) falls back to minimum available (5 fps)
        assertEquals(UvcFormat(1920, 1080, 5, "YUV2"), UvcFormat.findBestFormatForFps(2, formats))
    }
}

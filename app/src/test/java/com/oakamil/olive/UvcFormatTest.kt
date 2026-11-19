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
            assertTrue(
                "Fallback format $format must fit USB 2.0 bandwidth",
                format.fitsUsb2Bandwidth()
            )
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
        assertEquals(
            UvcFormat(1920, 1080, 30, "MJPEG"),
            UvcFormat.findBestFormatForFps(30, formats)
        )
        assertEquals(UvcFormat(960, 540, 15, "YUV2"), UvcFormat.findBestFormatForFps(15, formats))

        // Next fastest matching FPS when target is not available
        // Target 25 fps falls back to 20 fps
        assertEquals(
            UvcFormat(1920, 1080, 20, "MJPEG"),
            UvcFormat.findBestFormatForFps(25, formats)
        )
        // Target 12 fps falls back to 10 fps
        assertEquals(
            UvcFormat(1920, 1080, 10, "MJPEG"),
            UvcFormat.findBestFormatForFps(12, formats)
        )
        // Target 2 fps (below minimum 5) falls back to minimum available (5 fps)
        assertEquals(UvcFormat(1920, 1080, 5, "YUV2"), UvcFormat.findBestFormatForFps(2, formats))
    }

    @Test
    fun testUsb3FormatSelection() {
        val inputList = listOf(
            UvcFormat(1920, 1080, 30, "MJPEG"),
            UvcFormat(1920, 1080, 30, "YUV2"), // On USB 2.0 this was excluded; on USB 3.0 it wins!
            UvcFormat(1920, 1080, 15, "YUV2"),
            UvcFormat(1920, 1080, 5, "YUV2")
        )

        val usb2Filtered = UvcFormat.filterFormats(inputList, isUsb3 = false)
        // On USB 2.0, 30 fps falls back to MJPEG because YUV2 exceeds 24.576 MB/s
        assertEquals(UvcFormat(1920, 1080, 30, "MJPEG"), usb2Filtered[0])

        val usb3Filtered = UvcFormat.filterFormats(inputList, isUsb3 = true)
        // On USB 3.0, 30 fps picks uncompressed YUV2 over MJPEG
        assertEquals(UvcFormat(1920, 1080, 30, "YUV2"), usb3Filtered[0])
        assertEquals(UvcFormat(1920, 1080, 15, "YUV2"), usb3Filtered[1])
        assertEquals(UvcFormat(1920, 1080, 5, "YUV2"), usb3Filtered[2])
    }

    @Test
    fun testIsUsb3Descriptors() {
        // USB 2.0 descriptors (e.g. Standard Device + Interface + Isochronous Endpoint, no 0x30)
        val usb2Raw = byteArrayOf(
            18,
            0x01,
            0x10,
            0x02,
            0,
            0,
            0,
            64,
            1,
            2,
            3,
            4,
            0,
            1,
            1,
            2,
            0,
            1, // Device Desc (bcdUSB 0x0210)
            9,
            0x02,
            32,
            0,
            1,
            1,
            0,
            0x80.toByte(),
            50,                         // Config Desc
            7,
            0x05,
            0x81.toByte(),
            0x05,
            0x00,
            0x0c,
            1                         // Endpoint Desc (0x05)
        )
        assertFalse(UvcFormat.isUsb3Descriptors(usb2Raw))
        assertFalse(UvcFormat.isUsb3Descriptors(null))
        assertFalse(UvcFormat.isUsb3Descriptors(byteArrayOf(1, 2)))

        // USB 3.0 descriptors (Device + Config + Endpoint + SuperSpeed Endpoint Companion Desc 0x30)
        val usb3Raw = byteArrayOf(
            18,
            0x01,
            0x00,
            0x03,
            0,
            0,
            0,
            9,
            1,
            2,
            3,
            4,
            0,
            1,
            1,
            2,
            0,
            1,  // Device Desc (bcdUSB 0x0300)
            9,
            0x02,
            38,
            0,
            1,
            1,
            0,
            0x80.toByte(),
            50,                        // Config Desc
            7,
            0x05,
            0x81.toByte(),
            0x05,
            0x00,
            0x04,
            1,                        // Endpoint Desc (0x05)
            6,
            0x30,
            15,
            2,
            0x00,
            0x00                                         // SS Endpoint Companion Desc (0x30)
        )
        assertTrue(UvcFormat.isUsb3Descriptors(usb3Raw))
    }

    @Test
    fun testDualModeMjpegAndYuv2Discovery() {
        // Innomaker U20CAM dual-mode configuration on USB 2.0:
        // 1080p @ 30 fps MJPEG, 1080p @ 5 fps YUV2
        val inputList = listOf(
            UvcFormat(1920, 1080, 30, "MJPEG"),
            UvcFormat(1920, 1080, 5, "YUV2")
        )
        val filtered = UvcFormat.filterFormats(inputList)
        assertEquals(2, filtered.size)
        assertEquals(UvcFormat(1920, 1080, 30, "MJPEG"), filtered[0])
        assertEquals(UvcFormat(1920, 1080, 5, "YUV2"), filtered[1])
    }

    @Test
    fun testMacroblock16BinnedAlignment() {
        // Encoders with macroblock-16 alignment output 960x544 instead of 960x540
        val inputList = listOf(
            UvcFormat(1920, 1080, 30, "MJPEG"),
            UvcFormat(960, 544, 20, "YUV2"), // Macroblock-16 vertical alignment (544 vs 540)
            UvcFormat(1920, 1080, 5, "YUV2")
        )
        val filtered = UvcFormat.filterFormats(inputList)
        assertEquals(3, filtered.size)
        assertEquals(UvcFormat(1920, 1080, 30, "MJPEG"), filtered[0])
        assertEquals(UvcFormat(960, 544, 20, "YUV2"), filtered[1])
        assertEquals(UvcFormat(1920, 1080, 5, "YUV2"), filtered[2])
    }

    @Test
    fun testBinnedModeSelectionHierarchy() {
        // At 30 fps: 1x (1080p MJPEG) beats 2x2 binned (960x540 MJPEG)
        // At 20 fps: 1x is NOT available (or exceeds bandwidth), so 2x2 binned is preserved
        val inputList = listOf(
            UvcFormat(1920, 1080, 30, "MJPEG"),
            UvcFormat(960, 540, 30, "MJPEG"),
            UvcFormat(960, 540, 20, "YUV2"),
            UvcFormat(1920, 1080, 5, "YUV2")
        )
        val filtered = UvcFormat.filterFormats(inputList)
        assertEquals(3, filtered.size)
        assertEquals(UvcFormat(1920, 1080, 30, "MJPEG"), filtered[0]) // 1x won at 30 fps
        assertEquals(
            UvcFormat(960, 540, 20, "YUV2"),
            filtered[1]
        )    // 2x2 binned allowed as sole candidate at 20 fps
        assertEquals(UvcFormat(1920, 1080, 5, "YUV2"), filtered[2])
    }
}


package com.oakamil.olive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan
import kotlin.math.round
import kotlin.math.tan

class ZoomFovTest {

    private fun calculateZoomedFov(baseFovDeg: Double, zoom: Double): Double {
        val baseFovRad = Math.toRadians(baseFovDeg)
        val zoomedFovRad = 2.0 * atan(tan(baseFovRad / 2.0) / zoom)
        return Math.toDegrees(zoomedFovRad)
    }

    private fun calculateBaseFov(zoomedFovDeg: Double, zoom: Double): Double {
        val zoomedFovRad = Math.toRadians(zoomedFovDeg)
        val baseFovRad = 2.0 * atan(tan(zoomedFovRad / 2.0) * zoom)
        return Math.toDegrees(baseFovRad)
    }

    @Test
    fun testRectilinearFovProjectionAtUnityZoom() {
        val baseFov = 65.0
        val zoomedFov = calculateZoomedFov(baseFov, 1.0)
        assertEquals(65.0, zoomedFov, 1e-4)
    }

    @Test
    fun testRectilinearFovProjectionAtTwoXZoom() {
        val baseFov = 65.0
        val zoomedFov = calculateZoomedFov(baseFov, 2.0)
        // tan(65° / 2) = tan(32.5°) ≈ 0.63707
        // 0.63707 / 2 = 0.318535
        // 2 * atan(0.318535) ≈ 35.34° (or 35.81° depending on precision)
        // 2 * atan(tan(32.5° * pi / 180) / 2) * 180 / pi ≈ 35.342°
        val recoveredBase = calculateBaseFov(zoomedFov, 2.0)
        assertEquals(baseFov, recoveredBase, 1e-4)
        assertTrue("Zoomed FOV must be smaller than base FOV", zoomedFov < baseFov)
        assertTrue("Zoomed FOV must remain above catalog min_fov of 10.0°", zoomedFov >= 10.0)
    }

    @Test
    fun testRectilinearFovProjectionRoundTripIntermediateZooms() {
        val baseFov = 72.0
        for (i in 10..20) {
            val zoom = i / 10.0
            val zoomedFov = calculateZoomedFov(baseFov, zoom)
            val recovered = calculateBaseFov(zoomedFov, zoom)
            assertEquals("Round trip for zoom $zoom must match base FOV", baseFov, recovered, 1e-4)
        }
    }

    @Test
    fun testZoomSliderDiscreteSteps() {
        val effectiveMaxZoom = 2.0f
        val stepsCount = (round((effectiveMaxZoom - 1.0f) * 10f).toInt() - 1).coerceAtLeast(0)
        assertEquals("There should be 9 discrete steps between 1.0x and 2.0x for 0.1 increments", 9, stepsCount)

        // Verify stepping quantization
        val rawValues = listOf(1.04f, 1.08f, 1.49f, 1.51f, 1.95f, 2.04f)
        val expected = listOf(1.0f, 1.1f, 1.5f, 1.5f, 2.0f, 2.0f)
        for (idx in rawValues.indices) {
            val stepped = (round(rawValues[idx] * 10.0f) / 10.0f).coerceIn(1.0f, 2.0f)
            assertEquals(expected[idx], stepped, 1e-4f)
        }
    }

    @Test
    fun testClampingWhenDeviceMaxZoomIsLimited() {
        val limitedMaxZoom = 1.5f
        val effectiveMax = minOf(2.0f, limitedMaxZoom)
        assertEquals(1.5f, effectiveMax, 1e-4f)
        val stepsCount = (round((effectiveMax - 1.0f) * 10f).toInt() - 1).coerceAtLeast(0)
        assertEquals(4, stepsCount) // 1.1, 1.2, 1.3, 1.4
    }
}

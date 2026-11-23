// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // Rectilinear 2x zoom: 2 * atan(tan(65° / 2) / 2) ≈ 35.34°
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
        assertEquals(
            "There should be 9 discrete steps between 1.0x and 2.0x for 0.1 increments",
            9,
            stepsCount
        )

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

    @Test
    fun testEngineStatsCameraViewAndTargetMatch() {
        val solved = EngineStats(
            ra = 100.0,
            dec = 20.0,
            centerRa = 100.0,
            centerDec = 20.0,
            fov = 10.0,
            solvedCrop = "Full",
            targetRa = 150.0,
            targetDec = -10.0,
        )
        // Any object within fov * 0.75 (7.5°) is in camera view, even if not the GoTo target
        assertTrue(solved.isCoordInCameraView(100.0, 25.0))
        assertFalse(solved.isCoordInCameraView(100.0, 28.0))
        assertFalse(solved.isTargetVisible)

        // IMU estimate after solve remains active (uses ra/dec)
        val imuTracking = solved.copy(isImuEstimate = true, solvedCrop = EngineStats.CROP_FAILED)
        assertTrue(imuTracking.isCoordInCameraView(100.0, 25.0))

        // Failed solve without IMU is stale -> not in camera view
        val stale = solved.copy(isImuEstimate = false, solvedCrop = EngineStats.CROP_FAILED)
        assertFalse(stale.isCoordInCameraView(100.0, 20.0))

        // Before first solve (null ra/dec or null fov) -> not in camera view
        assertFalse(EngineStats().isCoordInCameraView(100.0, 20.0))

        // Target match threshold (< 0.05°)
        assertTrue(solved.isTargetMatch(150.0, -10.0))
        assertTrue(solved.isTargetMatch(150.02, -10.02))
        assertFalse(solved.isTargetMatch(150.2, -10.0))
        assertFalse(EngineStats().isTargetMatch(150.0, -10.0))
    }
}

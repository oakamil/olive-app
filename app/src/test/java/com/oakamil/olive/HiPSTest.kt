// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class HiPSTest {

    private class MapStorage : KeyValueStorage {
        private val map = HashMap<String, Any>()
        override fun getString(key: String, defValue: String?) = map[key] as? String ?: defValue
        override fun putString(key: String, value: String) {
            map[key] = value
        }

        override fun getInt(key: String, defValue: Int) = map[key] as? Int ?: defValue
        override fun putInt(key: String, value: Int) {
            map[key] = value
        }

        override fun getBoolean(key: String, defValue: Boolean) = map[key] as? Boolean ?: defValue
        override fun putBoolean(key: String, value: Boolean) {
            map[key] = value
        }

        override fun getFloat(key: String, defValue: Float) = map[key] as? Float ?: defValue
        override fun putFloat(key: String, value: Float) {
            map[key] = value
        }

        override fun getStringSet(key: String, defValue: Set<String>?) =
            @Suppress("UNCHECKED_CAST") (map[key] as? Set<String> ?: defValue)

        override fun putStringSet(key: String, value: Set<String>) {
            map[key] = value
        }

        override fun contains(key: String) = map.containsKey(key)
        override fun remove(key: String) {
            map.remove(key)
        }
    }

    @Test
    fun testHealpixRoundTripAllOrders() {
        val out = IntArray(3)
        for (order in Healpix.MIN_ORDER..Healpix.HQ_MAX_ORDER) {
            val nside = Healpix.nsideForOrder(order)
            val invNside = 1.0 / nside.toDouble()
            val npix = Healpix.tileCountForOrder(order)
            val step = when {
                order <= 2 -> 1
                order == 3 -> 7
                order == 4 -> 29
                else -> (npix / 251).coerceAtLeast(1)
            }
            var ipix = 0
            while (ipix < npix) {
                Healpix.nestToFaceIxIy(order, ipix, out)
                assertEquals(ipix, Healpix.faceIxIyToNest(order, out[0], out[1], out[2]))

                val (raDeg, decDeg) = Healpix.faceUvToRaDecDeg(
                    out[0],
                    (out[1] + 0.5) * invNside,
                    (out[2] + 0.5) * invNside
                )
                val recovered = Healpix.ang2pixNest(order, raDeg, decDeg)
                assertEquals("Order $order pixel $ipix center ($raDeg, $decDeg)", ipix, recovered)
                ipix += step
            }
        }
    }

    @Test
    fun testHealpixBaseEdgeContinuity() {
        val v0 = DoubleArray(3)
        val v1 = DoubleArray(3)
        // Shared edge between Face 0 East (fx=1, fy=t) and Face 1 West (fx=t, fy=1)
        for (k in 0..10) {
            val t = k / 10.0
            Healpix.faceUvToEqUnitVector(0, 1.0, t, v0)
            Healpix.faceUvToEqUnitVector(1, t, 1.0, v1)
            assertEquals(v0[0], v1[0], 1e-12)
            assertEquals(v0[1], v1[1], 1e-12)
            assertEquals(v0[2], v1[2], 1e-12)
        }
    }

    @Test
    fun testVisibleTilesIncludePolesAndWrapSeam() {
        val indices = IntArray(Healpix.tileCountForOrder(Healpix.MAX_ORDER))
        val northPoleCount = Healpix.queryVisibleTiles(Healpix.MAX_ORDER, 0.0, 89.95, 10.0, indices)
        assertTrue(northPoleCount >= 4)

        val southPoleCount = Healpix.queryVisibleTiles(3, 180.0, -89.95, 10.0, indices)
        assertTrue(southPoleCount >= 4)

        val eqWrapTile = Healpix.ang2pixNest(2, 359.95, 0.05)
        val countWrap = Healpix.queryVisibleTiles(2, 0.0, 0.0, 15.0, indices)
        assertTrue(indices.take(countWrap).contains(eqWrapTile))

        val allSky0 = Healpix.queryVisibleTiles(0, 120.0, 20.0, 180.0, indices)
        assertEquals(12, allSky0)
    }

    @Test
    fun testEqToScreenProjectorMatchesSkyMapProjection() {
        val lat = 37.3382
        val lst = 142.5
        val centerAz = 195.0
        val centerAlt = 42.0
        val fov = 55.0
        val width = 1080f
        val height = 2200f

        val skyProj = SkyMapProjection(centerAz, centerAlt, fov, width, height)
        val eqProj = EqToScreenProjector().apply {
            configure(centerAz, centerAlt, fov, width, height, lat, lst)
        }

        val coords = FloatArray(2)
        val samples = listOf(
            83.8 to -5.4,
            266.4 to -29.0,
            10.68 to 41.27,
            37.95 to 89.26,
            0.0 to 0.0,
            359.5 to -65.0
        )
        for ((ra, dec) in samples) {
            val (az, alt) = AstrometryUtils.raDecToAltAz(ra, dec, lat, lst)
            val expected = skyProj.toScreenOffset(az, alt)
            val raRad = ra * PI / 180.0
            val decRad = dec * PI / 180.0
            val qx = cos(decRad) * cos(raRad)
            val qy = cos(decRad) * sin(raRad)
            val qz = sin(decRad)
            val sinAlt = eqProj.project(qx, qy, qz, coords, 0)
            if (expected.x.isNaN()) {
                assertTrue(sinAlt.isNaN())
            } else {
                assertTrue(!sinAlt.isNaN())
                assertEquals("X mismatch for ($ra,$dec)", expected.x, coords[0], 1e-2f)
                assertEquals("Y mismatch for ($ra,$dec)", expected.y, coords[1], 1e-2f)
                assertEquals("sinAlt mismatch for ($ra,$dec)", sin(alt * PI / 180.0), sinAlt, 1e-9)
            }
        }
    }

    @Test
    fun testLodHysteresisStability() {
        // Promotions on zoom in (1080px screen, test with maxOrder = 3 and maxOrder = 4)
        assertEquals(
            0,
            HiPSTileManager.computeNextOrderWithHysteresis(0, 400.0, 120.0, maxOrder = 3)
        )
        assertEquals(
            1,
            HiPSTileManager.computeNextOrderWithHysteresis(0, 200.0, 60.0, maxOrder = 3)
        )
        assertEquals(
            2,
            HiPSTileManager.computeNextOrderWithHysteresis(1, 100.0, 30.0, maxOrder = 3)
        )
        assertEquals(3, HiPSTileManager.computeNextOrderWithHysteresis(2, 50.0, 15.0, maxOrder = 3))

        // Deadband stability: stay at Order 3 between 85..100 arcsec/px and fov 26..30 deg
        assertEquals(3, HiPSTileManager.computeNextOrderWithHysteresis(3, 95.0, 28.0, maxOrder = 3))
        assertEquals(2, HiPSTileManager.computeNextOrderWithHysteresis(2, 95.0, 28.0, maxOrder = 3))

        // Demotions on zoom out
        assertEquals(
            2,
            HiPSTileManager.computeNextOrderWithHysteresis(3, 105.0, 31.0, maxOrder = 3)
        )
        assertEquals(
            1,
            HiPSTileManager.computeNextOrderWithHysteresis(2, 220.0, 62.0, maxOrder = 3)
        )
        assertEquals(
            0,
            HiPSTileManager.computeNextOrderWithHysteresis(1, 450.0, 130.0, maxOrder = 3)
        )

        // Order 4 hysteresis transitions
        assertEquals(4, HiPSTileManager.computeNextOrderWithHysteresis(3, 35.0, 10.0, maxOrder = 4))
        assertEquals(4, HiPSTileManager.computeNextOrderWithHysteresis(4, 46.0, 14.0, maxOrder = 4))
        assertEquals(3, HiPSTileManager.computeNextOrderWithHysteresis(4, 55.0, 16.0, maxOrder = 4))
    }

    @Test
    fun testSettingsDefaultShowSkyBackgroundFalse() {
        val settings = SettingsManager(MapStorage())
        assertFalse(settings.showSkyBackground)
        settings.showSkyBackground = true
        assertTrue(settings.showSkyBackground)
        settings.showSkyBackground = false
        assertFalse(settings.showSkyBackground)
    }

    @Test
    fun testMaxOrderHelpersAndDecimalMbFormatting() {
        assertEquals(1020, Healpix.totalTilesUpTo(3))
        assertEquals(4092, Healpix.totalTilesUpTo(4))
        assertEquals(4 to 0, HiPSDownloadManager.globalIndexToOrderIpix(1020, maxOrder = 4))
        assertEquals(4 to 3071, HiPSDownloadManager.globalIndexToOrderIpix(4091, maxOrder = 4))

        assertEquals("0.0", HiPSDownloadManager.formatMbValue(0L))
        assertEquals("0.1", HiPSDownloadManager.formatMbValue(1_024L))
        assertEquals("44.1", HiPSDownloadManager.formatMbValue(44_077_440L))
        assertEquals(45, HiPSDownloadManager.estimatedDownloadMb(3))
        assertEquals(215, HiPSDownloadManager.estimatedDownloadMb(4))
        assertEquals("1,020", HiPSDownloadManager.formatCount(1020))
        assertEquals("4,092", HiPSDownloadManager.formatCount(4092))
    }

    @Test
    fun testHiPSPackTocJpegBuildAndValidation() {
        val tileCount = Healpix.TOTAL_TILES
        val headerBytes = HiPSPackToc.buildHeaderBytes()
        assertEquals(Healpix.MAX_ORDER, HiPSPackToc.parseHeader(headerBytes))

        // Reject out-of-range maxOrder (> Healpix.MAX_ORDER) to guard shift/alloc overflow
        val badMaxOrderHeader = HiPSPackToc.buildHeaderBytes(
            maxOrder = 10,
            tileWidth = 512,
            tileFormat = HiPSPackToc.TILE_FORMAT_JPEG,
            tileCount = Healpix.totalTilesUpTo(10)
        )
        assertEquals(null, HiPSPackToc.parseHeader(badMaxOrderHeader))

        val badFormatHeader = HiPSPackToc.buildHeaderBytes(
            maxOrder = Healpix.MAX_ORDER,
            tileWidth = 512,
            tileFormat = 99,
            tileCount = tileCount
        )
        assertEquals(null, HiPSPackToc.parseHeader(badFormatHeader))

        val dataStart =
            HiPSPackToc.HEADER_BYTES.toLong() + tileCount.toLong() * HiPSPackToc.TOC_ENTRY_BYTES
        val offsets = LongArray(tileCount)
        val lengths = IntArray(tileCount)
        offsets[0] = dataStart
        lengths[0] = 256
        offsets[1] = dataStart + 256L
        lengths[1] = 512
        val tocBytes = HiPSPackToc.buildTocBytes(offsets, lengths)
        val totalLen = dataStart + 256L + 512L

        val toc = HiPSPackToc.parse(headerBytes, tocBytes)!!
        assertTrue(toc.isValid(totalLen, Healpix.MAX_ORDER))
        assertFalse(toc.isValid(totalLen, Healpix.MAX_ORDER + 1))
        assertEquals(null, HiPSPackToc.parse(badFormatHeader, tocBytes))
        assertFalse(toc.isValid(totalLen - 1L, Healpix.MAX_ORDER))

        // Reject signed 64-bit arithmetic overflow offset (off + len wraps negative)
        val overflowOffsets = offsets.copyOf()
        overflowOffsets[0] = Long.MAX_VALUE - 10L
        val overflowToc = HiPSPackToc.buildTocBytes(overflowOffsets, lengths)
        assertFalse(
            HiPSPackToc.parse(headerBytes, overflowToc)!!.isValid(totalLen, Healpix.MAX_ORDER)
        )
    }

    private fun fakeJpeg(seed: Int, size: Int = 256): ByteArray {
        val out = ByteArray(size) { ((it + seed) and 0x7F).toByte() }
        out[0] = 0xFF.toByte()
        out[1] = 0xD8.toByte()
        out[2] = 0xFF.toByte()
        out[3] = 0xE0.toByte()
        out[4] = (seed and 0xFF).toByte()
        out[5] = ((seed ushr 8) and 0xFF).toByte()
        return out
    }

    private class FakeNetworkBackend(
        private val delegate: AndroidHiPSLocalBackend,
        private val responder: (String) -> ByteArray?
    ) : HiPSLocalBackend by delegate {
        override suspend fun fetchUrlBytes(url: String): ByteArray? = responder(url)
    }

    @Test
    fun testHiPSDownloadManagerMirrorFallbackResumeAndPackReadBack() = runBlocking {
        val tempRoot = Files.createTempDirectory("hips_dl_test").toFile()
        try {
            val diskBackend = AndroidHiPSLocalBackend(tempRoot)
            assertEquals(0L, diskBackend.downloadedPackSizeBytes())

            val failingTile = HiPSDownloadManager.globalIndexToOrderIpix(500)
            val failingPathSuffix =
                "/Norder${failingTile.first}/Dir${(failingTile.second / 10_000) * 10_000}/Npix${failingTile.second}.jpg"

            // First run: fail tile 500 across both mirrors; all other tiles must remain staged for resume
            val states1 = mutableListOf<HiPSDownloadState>()
            val backend1 = FakeNetworkBackend(diskBackend) { url ->
                if (url.endsWith(failingPathSuffix)) null else fakeJpeg(url.hashCode())
            }
            val ok1 = HiPSDownloadManager.downloadDss2(
                backend = backend1,
                mirrorBaseUrls = listOf("https://mirror-a.example", "https://mirror-b.example"),
                callbackDispatcher = Dispatchers.Default
            ) { states1.add(it) }

            assertFalse(ok1)
            assertEquals(0L, diskBackend.downloadedPackSizeBytes())
            val lastState1 = states1.last()
            assertTrue(
                "Expected Failed state, got $lastState1",
                lastState1 is HiPSDownloadState.Failed
            )
            val failedState = lastState1 as HiPSDownloadState.Failed
            assertEquals(Healpix.TOTAL_TILES - 1, failedState.completedTiles)
            assertEquals(HiPSFailureReason.TileDownloadFailed(1), failedState.reason)
            val statsAfterFail1 = diskBackend.getStagedStats()
            assertEquals(Healpix.TOTAL_TILES - 1, statsAfterFail1.flags.count { it })
            assertTrue(statsAfterFail1.totalBytes > 0L)

            // Second run: mirror-a fails tile 500, mirror-b succeeds; only missing tile 500 is fetched
            val fetchCalls = AtomicInteger(0)
            val expectedTile500 = fakeJpeg(500, size = 320)
            val states2 = mutableListOf<HiPSDownloadState>()
            val backend2 = FakeNetworkBackend(diskBackend) { url ->
                fetchCalls.incrementAndGet()
                assertTrue(
                    "Expected resume to fetch only tile 500, got $url",
                    url.endsWith(failingPathSuffix)
                )
                if (url.startsWith("https://mirror-a.example")) null else expectedTile500
            }
            val ok2 = HiPSDownloadManager.downloadDss2(
                backend = backend2,
                mirrorBaseUrls = listOf("https://mirror-a.example", "https://mirror-b.example"),
                callbackDispatcher = Dispatchers.Default
            ) { states2.add(it) }

            assertTrue(ok2)
            assertEquals(
                2,
                fetchCalls.get()
            ) // 1 failed mirror-a attempt + 1 succeeded mirror-b fallback
            assertTrue(diskBackend.downloadedPackSizeBytes() > 0L)
            assertEquals(0L, diskBackend.getStagedStats().totalBytes)
            assertEquals(HiPSDownloadState.Idle, states2.last())

            // Read back assembled pack via AndroidHiPSAssetSource and verify header + tile 500 payload
            val source = diskBackend.openDownloadedAssetSource()
            try {
                val toc = HiPSPackToc.readFrom(source)!!
                assertEquals(Healpix.TOTAL_TILES, toc.tileCount)
                val payload500 = source.readRange(toc.offsets[500], toc.lengths[500])
                assertArrayEquals(expectedTile500, payload500)
            } finally {
                source.close()
            }

            // Removal cleans up pack completely
            assertTrue(diskBackend.deleteDownloadedPackAndStaging())
            assertEquals(0L, diskBackend.downloadedPackSizeBytes())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun testHiPSDownloadManagerRejectsHtmlErrorPayload() = runBlocking {
        val tempRoot = Files.createTempDirectory("hips_invalid_test").toFile()
        try {
            val diskBackend = AndroidHiPSLocalBackend(tempRoot)
            val backend = FakeNetworkBackend(diskBackend) { url ->
                if (url.endsWith("/Norder0/Dir0/Npix0.jpg")) {
                    "<html>503 Service Unavailable</html>".repeat(8).encodeToByteArray()
                } else {
                    fakeJpeg(url.hashCode())
                }
            }
            val states = mutableListOf<HiPSDownloadState>()
            val ok = HiPSDownloadManager.downloadDss2(
                backend = backend,
                mirrorBaseUrls = listOf("https://mirror-a.example"),
                callbackDispatcher = Dispatchers.Default
            ) { states.add(it) }

            assertFalse(ok)
            assertEquals(0L, diskBackend.downloadedPackSizeBytes())
            val partialStats = diskBackend.getStagedStats()
            assertEquals(Healpix.TOTAL_TILES - 1, partialStats.flags.count { it })
            assertTrue(partialStats.totalBytes > 0L)
            assertTrue(states.last() is HiPSDownloadState.Failed)

            // Single-tap partial removal deletes all staged tiles cleanly
            assertTrue(diskBackend.deleteDownloadedPackAndStaging())
            val afterClear = diskBackend.getStagedStats()
            assertEquals(0, afterClear.flags.count { it })
            assertEquals(0L, afterClear.totalBytes)

            // Failed assembly from incomplete staging leaves no orphan .pack.tmp behind
            assertTrue(diskBackend.writeStagedTile(0, fakeJpeg(0)))
            assertFalse(diskBackend.assemblePackFromStaging {})
            val leftoverTmp = tempRoot.listFiles()?.any { it.name.endsWith(".pack.tmp") } ?: false
            assertFalse("Expected failed assembly to delete temp pack cleanly", leftoverTmp)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun setRgba(
        rgba: ByteArray,
        width: Int,
        x: Int,
        y: Int,
        r: Int,
        g: Int,
        b: Int,
        a: Int = 255
    ) {
        val p = (y * width + x) * 4
        rgba[p] = r.toByte()
        rgba[p + 1] = g.toByte()
        rgba[p + 2] = b.toByte()
        rgba[p + 3] = a.toByte()
    }

    private fun channel(rgba: ByteArray, width: Int, x: Int, y: Int, c: Int): Int {
        return rgba[(y * width + x) * 4 + c].toInt() and 0xFF
    }

    @Test
    fun testTileNormalizerFlattensDiagonalPlateSeamAndStraddlingStar() {
        val w = 128
        val h = 128
        val rgba = ByteArray(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val isHazySide = x + y >= w
                if (isHazySide) {
                    setRgba(rgba, w, x, y, 10, 14, 21)
                } else {
                    setRgba(rgba, w, x, y, 2, 3, 2)
                }
            }
        }
        // Plant a compact star halo centered right on the diagonal seam & cell boundary (64, 64)
        for (dy in -2..2) {
            for (dx in -2..2) {
                val distSq = dx * dx + dy * dy
                if (distSq <= 4) {
                    val b = 44 - distSq * 4
                    setRgba(rgba, w, 64 + dx, 64 + dy, b - 8, b - 4, b)
                }
            }
        }

        HiPSTileNormalizer.normalizeRgba(rgba, w, h)

        var maxDiffOnSeam = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x in 62..66 && y in 62..66) continue
                val r = channel(rgba, w, x, y, 0)
                val g = channel(rgba, w, x, y, 1)
                val b = channel(rgba, w, x, y, 2)
                val maxCh = maxOf(r, g, b)
                if (x + y >= w) {
                    assertTrue(
                        "Expected hazy diagonal plate flattened <= 5, got ($r,$g,$b) at ($x,$y)",
                        maxCh <= 5
                    )
                } else {
                    assertEquals(2, r)
                    assertEquals(3, g)
                    assertEquals(2, b)
                }
                if (x + y in (w - 2)..(w + 2)) {
                    maxDiffOnSeam = maxOf(maxDiffOnSeam, maxCh)
                }
            }
        }
        assertTrue(
            "Expected no bright straddling fringe near seam, max=$maxDiffOnSeam",
            maxDiffOnSeam <= 5
        )

        // Straddling star keeps its center peak without an own-cell step ring across x = 63 -> 64 -> 65
        val leftWingB = channel(rgba, w, 63, 64, 2)
        val centerB = channel(rgba, w, 64, 64, 2)
        val rightWingB = channel(rgba, w, 65, 64, 2)
        assertTrue(
            "Expected star center ($centerB) brighter than wings ($leftWingB, $rightWingB)",
            centerB > leftWingB && centerB > rightWingB
        )
        assertTrue(
            "Expected symmetric halo across seam cell boundary, got $leftWingB vs $rightWingB",
            abs(leftWingB - rightWingB) <= 3
        )
    }

    @Test
    fun testTileNormalizerPreservesNebulaeTapersHighlightsAndKeepsAlpha() {
        val w = 96
        val h = 64
        val rgba = ByteArray(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                when {
                    x < 32 -> setRgba(
                        rgba,
                        w,
                        x,
                        y,
                        26,
                        17,
                        7
                    )      // Warm Milky Way dust (R - B >= 10)
                    x < 64 -> setRgba(
                        rgba,
                        w,
                        x,
                        y,
                        44,
                        114,
                        180
                    )   // Bright reflection nebula (max >= 36)
                    else -> setRgba(
                        rgba,
                        w,
                        x,
                        y,
                        10,
                        14,
                        20
                    )       // Cool hazy plate (sub = 6, 10, 16)
                }
            }
        }
        // Odd y-coordinates avoid the even 4x4 block sample grid while testing pixel-level rules on a hazy plate
        setRgba(rgba, w, 88, 11, 200, 210, 232)
        setRgba(rgba, w, 88, 21, 255, 255, 255)
        setRgba(rgba, w, 88, 31, 10, 14, 20, 173)

        HiPSTileNormalizer.normalizeRgba(rgba, w, h)

        assertEquals(26, channel(rgba, w, 16, 20, 0))
        assertEquals(17, channel(rgba, w, 16, 20, 1))
        assertEquals(7, channel(rgba, w, 16, 20, 2))

        assertEquals(44, channel(rgba, w, 40, 20, 0))
        assertEquals(114, channel(rgba, w, 40, 20, 1))
        assertEquals(180, channel(rgba, w, 40, 20, 2))

        // Highlight taper (pMax = 232 > 210) subtracts a tapered fraction rather than full 16 or 0
        val taperedB = channel(rgba, w, 88, 11, 2)
        assertTrue("Expected tapered highlight in 217..231, got $taperedB", taperedB in 217..231)

        // Saturated 255 core is untouched even inside an active hazy plate
        assertEquals(255, channel(rgba, w, 88, 21, 0))
        assertEquals(255, channel(rgba, w, 88, 21, 1))
        assertEquals(255, channel(rgba, w, 88, 21, 2))

        // Non-255 alpha is preserved while RGB pedestal is normalized to TARGET_FLOOR (4)
        assertEquals(4, channel(rgba, w, 88, 31, 0))
        assertEquals(4, channel(rgba, w, 88, 31, 1))
        assertEquals(4, channel(rgba, w, 88, 31, 2))
        assertEquals(173, channel(rgba, w, 88, 31, 3))
    }

    @Test
    fun testTileNormalizerSmoothRampMonotonicityAndDimensionGuards() {
        val w = 64
        val h = 32
        val ramp = ByteArray(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val b = when {
                    x < 16 -> 20
                    x < 48 -> 20 + (x - 15)
                    else -> 52
                }
                setRgba(ramp, w, x, y, b - 10, b - 6, b)
            }
        }

        HiPSTileNormalizer.normalizeRgba(ramp, w, h)

        var prevB = channel(ramp, w, 0, 16, 2)
        assertEquals(4, prevB)
        for (x in 1 until w) {
            val curB = channel(ramp, w, x, 16, 2)
            assertTrue("Expected monotonic ramp at x=$x: prev=$prevB, cur=$curB", curB >= prevB)
            assertTrue(
                "Expected smooth transition without step jump at x=$x: prev=$prevB, cur=$curB",
                curB - prevB <= 6
            )
            prevB = curB
        }
        assertEquals(52, prevB)

        // Non-multiple-of-16 dimensions (40x40 -> 3x3 cells with 8px edge cells) normalize cleanly
        val nonMult = ByteArray(40 * 40 * 4)
        for (y in 0 until 40) {
            for (x in 0 until 40) {
                setRgba(nonMult, 40, x, y, 10, 14, 20)
            }
        }
        HiPSTileNormalizer.normalizeRgba(nonMult, 40, 40)
        assertEquals(4, channel(nonMult, 40, 39, 39, 2))

        // Undersized tiles (< MIN_DIM) and 32-bit Int overflow dimensions return safely as no-ops
        val tiny = ByteArray(12 * 12 * 4)
        setRgba(tiny, 12, 5, 5, 10, 14, 20)
        HiPSTileNormalizer.normalizeRgba(tiny, 12, 12)
        assertEquals(20, channel(tiny, 12, 5, 5, 2))

        val overflowGuard = ByteArray(16) { 20 }
        HiPSTileNormalizer.normalizeRgba(overflowGuard, 32768, 32768)
        assertEquals(20, overflowGuard[0].toInt() and 0xFF)
    }

    private fun sampleObj(
        id: Int = 1,
        label: String = "M",
        entry: String = "57",
        ra: Double = 283.396,
        dec: Double = 33.029,
        type: String = "Planetary Nebula",
        size: String? = "1.4"
    ) = CatalogObject(
        id = id,
        catalogLabel = label,
        catalogEntry = entry,
        ra = ra,
        dec = dec,
        constellation = "Lyr",
        objectType = type,
        broadCategory = "nebula",
        magnitude = 8.8,
        surfaceBrightness = null,
        angularSize = size,
        commonName = null,
        description = null,
        copyright = null
    )

    @Test
    fun testHqTileKeyPackingObjectPlannerAndOrder8Hysteresis() {
        for (order in 0..Healpix.HQ_MAX_ORDER) {
            val maxIpix = Healpix.tileCountForOrder(order) - 1
            for (ipix in intArrayOf(0, maxIpix / 2, maxIpix)) {
                val packed = Healpix.packTileKey(order, ipix)
                assertEquals(order, Healpix.tileKeyOrder(packed))
                assertEquals(ipix, Healpix.tileKeyIpix(packed))
            }
        }

        assertTrue(HiPSObjectPlanner.isEligibleObject(sampleObj(type = "Galaxy")))
        assertTrue(HiPSObjectPlanner.isEligibleObject(sampleObj(type = "Dark Nebula")))
        assertTrue(HiPSObjectPlanner.isEligibleObject(sampleObj(type = "Asterism", size = "3.1°")))
        assertFalse(HiPSObjectPlanner.isEligibleObject(sampleObj(type = "Star")))
        assertFalse(HiPSObjectPlanner.isEligibleObject(sampleObj(type = "Multiple Star")))
        assertFalse(HiPSObjectPlanner.isEligibleObject(sampleObj(type = "Unknown")))
        assertFalse(
            HiPSObjectPlanner.isEligibleObject(
                sampleObj(
                    id = -1,
                    label = "Planet",
                    type = "Planet"
                )
            )
        )

        assertTrue(HiPSObjectPlanner.isEligibleSubCatalog("M"))
        assertTrue(HiPSObjectPlanner.isEligibleSubCatalog("SaA"))
        assertTrue(HiPSObjectPlanner.isEligibleSubCatalog("NGC"))
        assertFalse(HiPSObjectPlanner.isEligibleSubCatalog("Str"))
        assertFalse(HiPSObjectPlanner.isEligibleSubCatalog("SaM"))
        assertFalse(HiPSObjectPlanner.isEligibleSubCatalog(null))

        assertEquals(189.1, HiPSObjectPlanner.parseExtentArcmin("189.1x61.7")!!, 1e-6)
        assertEquals(186.0, HiPSObjectPlanner.parseExtentArcmin("3.1°")!!, 1e-6)
        assertEquals(0.5, HiPSObjectPlanner.parseExtentArcmin("30\"x20\"")!!, 1e-6)

        // Dynamic order mapping across scales (including 3.1° asterism -> Order 3)
        assertEquals(8, HiPSObjectPlanner.selectOrderAndFovDeg(1.4).first)    // M57
        assertEquals(7, HiPSObjectPlanner.selectOrderAndFovDeg(8.0).first)    // M27
        assertEquals(4, HiPSObjectPlanner.selectOrderAndFovDeg(85.0).first)   // M42
        assertEquals(3, HiPSObjectPlanner.selectOrderAndFovDeg(186.0).first)  // 3.1° asterism
        assertEquals(0, HiPSObjectPlanner.selectOrderAndFovDeg(1500.0).first) // 25° Big Dipper

        // Order <= 4 plans require 0 downloads when the O0-O4 base pack is already present
        val m31Plan = HiPSObjectPlanner.planCutout(10.6847, 41.2687, 189.1)
        assertEquals(3, m31Plan.order)
        assertTrue(m31Plan.requiredKeys.isNotEmpty())
        assertEquals(
            0,
            HiPSObjectPlanner.filterMissingKeys(
                m31Plan.requiredKeys,
                isBasePackAvailable = true,
                hqIndex = HiPSHqIndex.EMPTY
            ).size
        )
        assertEquals(
            m31Plan.requiredKeys.size,
            HiPSObjectPlanner.filterMissingKeys(
                m31Plan.requiredKeys,
                isBasePackAvailable = false,
                hqIndex = HiPSHqIndex.EMPTY
            ).size
        )

        // Hysteresis activates HQ (> Order 4) right past Order 4 native FOV (< 9.0° and < 26"/px)
        assertEquals(5, HiPSTileManager.computeNextOrderWithHysteresis(4, 24.5, 8.2, maxOrder = 8))
        assertEquals(5, HiPSTileManager.computeNextOrderWithHysteresis(5, 28.0, 9.8, maxOrder = 8))
        assertEquals(4, HiPSTileManager.computeNextOrderWithHysteresis(5, 31.0, 11.0, maxOrder = 8))
        assertEquals(6, HiPSTileManager.computeNextOrderWithHysteresis(5, 9.0, 2.8, maxOrder = 8))
        assertEquals(7, HiPSTileManager.computeNextOrderWithHysteresis(6, 4.5, 1.4, maxOrder = 8))
        assertEquals(
            8,
            HiPSTileManager.computeNextOrderWithHysteresis(
                4,
                1.67,
                0.5,
                maxOrder = Healpix.HQ_MAX_ORDER
            )
        )
        assertEquals(
            4,
            HiPSTileManager.computeNextOrderWithHysteresis(
                4,
                1.67,
                0.5,
                maxOrder = Healpix.MAX_ORDER
            )
        )

        // +1 HQ lookahead maps 0..4 -> 0..4, 5 -> 6, 6 -> 7, 7 -> 8, 8 -> 8
        for (ord in 0..Healpix.MAX_ORDER) {
            assertEquals(ord, HiPSTileManager.effectiveHqOrder(ord))
        }
        assertEquals(6, HiPSTileManager.effectiveHqOrder(5))
        assertEquals(7, HiPSTileManager.effectiveHqOrder(6))
        assertEquals(8, HiPSTileManager.effectiveHqOrder(7))
        assertEquals(8, HiPSTileManager.effectiveHqOrder(8))

        // Nested multi-order HQ tiles under one O4 base tile respect +1 lookahead ceilings
        val ipix4 = 42
        val ipix6 = (ipix4 shl 4) + 3
        val ipix8 = (ipix6 shl 4) + 5
        val keyO6 = Healpix.packTileKey(6, ipix6)
        val keyO8 = Healpix.packTileKey(8, ipix8)
        val nestedIdx = HiPSHqIndex.prepareAppend(
            HiPSHqIndex.EMPTY,
            listOf(keyO8 to ByteArray(16) { 2 }, keyO6 to ByteArray(16) { 1 })
        )!!.updatedIndex

        fun collectHqUnderO4(ceilingOrder: Int): List<Int> {
            val found = mutableListOf<Int>()
            for (childOrder in (Healpix.MAX_ORDER + 1)..ceilingOrder) {
                val shift = 2 * (childOrder - Healpix.MAX_ORDER)
                val minKey = Healpix.packTileKey(childOrder, ipix4 shl shift)
                val maxKey = Healpix.packTileKey(childOrder, ((ipix4 + 1) shl shift) - 1)
                nestedIdx.forEachKeyInRange(minKey, maxKey) { found.add(it) }
            }
            return found
        }
        assertEquals(listOf(keyO6), collectHqUnderO4(HiPSTileManager.effectiveHqOrder(5)))
        assertEquals(listOf(keyO6, keyO8), collectHqUnderO4(HiPSTileManager.effectiveHqOrder(7)))

        // Wide Order 0 preview (55° FOV on 550px box) steps cleanly through Orders 1..6 while zooming in
        var previewOrder = 0
        val visitedOrders = mutableListOf<Int>()
        for (zoom in floatArrayOf(1.1f, 2.2f, 4.5f, 9.0f, 18.0f, 30.0f)) {
            val fov = 55.0 / zoom
            val arcsecPx = (fov * 3600.0) / 550.0
            previewOrder = HiPSTileManager.computeNextOrderWithHysteresis(
                previewOrder,
                arcsecPx,
                fov,
                maxOrder = Healpix.HQ_MAX_ORDER
            )
            visitedOrders.add(previewOrder)
        }
        assertEquals(listOf(1, 2, 3, 4, 5, 6), visitedOrders)

        // Calibrated HQ tile byte estimates reflect empirical ESA DSSColor DSO tile sizes (>100 KB/tile)
        for (hqOrd in 5..8) {
            val singleTileBytes = HiPSDownloadManager.estimatedTilesBytes(
                intArrayOf(Healpix.packTileKey(hqOrd, 0))
            )
            assertTrue(
                "Expected Order $hqOrd estimate > 100 KB, got $singleTileBytes",
                singleTileBytes > 100_000L
            )
        }

        // Co-located size fallback and bulk catalog planner parity
        val unsizedArp = sampleObj(
            id = 10,
            label = "Arp",
            entry = "85",
            ra = 202.47,
            dec = 47.20,
            type = "Galaxy",
            size = null
        )
        val sizedNgc = sampleObj(
            id = 11,
            label = "NGC",
            entry = "5194",
            ra = 202.47,
            dec = 47.20,
            type = "Galaxy",
            size = "11.2x6.9"
        )
        val starRow = sampleObj(
            id = 12,
            label = "Str",
            entry = "1",
            ra = 202.47,
            dec = 47.20,
            type = "Star",
            size = null
        )
        assertEquals(
            11.2,
            HiPSObjectPlanner.resolveMajorAxisArcmin(unsizedArp, listOf(sizedNgc)),
            1e-6
        )
        assertEquals(1.5, HiPSObjectPlanner.resolveMajorAxisArcmin(unsizedArp, emptyList()), 1e-6)

        val singlePlan = HiPSObjectPlanner.planCutout(202.47, 47.20, 11.2)
        val bulkKeys = HiPSObjectPlanner.planMissingForCatalog(
            objects = listOf(unsizedArp, starRow),
            isBasePackAvailable = true,
            hqIndex = HiPSHqIndex.EMPTY,
            coLocatedLookup = { listOf(sizedNgc) }
        )
        assertArrayEquals(singlePlan.requiredKeys, bulkKeys)

        // Poles (dec = ±90°) and RA 0/360 wrap + 360° Zenith-up viewport corner coverage
        for ((ra, dec) in listOf(0.01 to 22.01, 359.98 to -45.0, 120.0 to 90.0, 240.0 to -90.0)) {
            val plan = HiPSObjectPlanner.planCutout(ra, dec, 12.0)
            assertTrue(plan.requiredKeys.isNotEmpty())
            val keySet = plan.requiredKeys.toSet()
            for (lst in listOf(0.0, 73.0, 185.0, 291.0)) {
                val (az0, alt0) = AstrometryUtils.raDecToAltAz(ra, dec, 37.7, lst)
                for (zoom in floatArrayOf(1f, 2.5f, 4f)) {
                    val fovNow = plan.baseFovDeg / zoom.toDouble()
                    val maxPan = plan.baseFovDeg * (0.5 * (1.0 - 1.0 / zoom.toDouble()) + 0.08)
                    val (panAz, panAlt) = panAndClampZenithUp(
                        curAzDeg = az0,
                        curAltDeg = alt0,
                        objAzDeg = az0,
                        objAltDeg = alt0,
                        deltaX = 80f,
                        deltaY = -60f,
                        fovDeg = fovNow,
                        minDimPx = 400f,
                        maxPanDeg = maxPan
                    )
                    val (panRa, panDec) = AstrometryUtils.altAzToRaDec(panAz, panAlt, 37.7, lst)
                    for ((cx, cy) in SkyMapProjection.computeCameraFovCorners(
                        panRa,
                        panDec,
                        fovNow,
                        0.0
                    )) {
                        val cornerKey =
                            Healpix.packTileKey(plan.order, Healpix.ang2pixNest(plan.order, cx, cy))
                        assertTrue(
                            "Expected corner ($cx,$cy) covered at zoom=$zoom lst=$lst",
                            cornerKey in keySet
                        )
                    }
                }
            }
        }

        // Zenith pole crossing in panAndClampZenithUp stays finite and clamped within maxPanDeg
        val (crossedAz, crossedAlt) = panAndClampZenithUp(
            curAzDeg = 10.0,
            curAltDeg = 89.6,
            objAzDeg = 10.0,
            objAltDeg = 89.6,
            deltaX = 0f,
            deltaY = 500f,
            fovDeg = 2.0,
            minDimPx = 300f,
            maxPanDeg = 1.2
        )
        assertTrue(crossedAz.isFinite() && crossedAlt.isFinite())
        val (r0, d0) = AstrometryUtils.altAzToRaDec(10.0, 89.6, 40.0, 20.0)
        val (r1, d1) = AstrometryUtils.altAzToRaDec(crossedAz, crossedAlt, 40.0, 20.0)
        assertEquals(1.2, AstrometryUtils.angularDistance(r0, d0, r1, d1), 0.05)
    }

    @Test
    fun testHqStoreIncrementalBatchesCrashRecoveryAndIndependentDeletion() = runBlocking {
        val tempRoot = Files.createTempDirectory("hips_hq_test").toFile()
        try {
            val diskBackend = AndroidHiPSLocalBackend(tempRoot)
            val targetKeys = IntArray(30) { i -> Healpix.packTileKey(8, 1000 + i) }
            val failingKey = targetKeys[17]
            val failingIpix = Healpix.tileKeyIpix(failingKey)
            val failingSuffix = "/Norder8/Dir0/Npix$failingIpix.jpg"

            // First pass: 29 of 30 succeed (> 25 batch size); committed tiles remain live for resume
            val backend1 = FakeNetworkBackend(diskBackend) { url ->
                if (url.endsWith(failingSuffix)) null else fakeJpeg(url.hashCode(), size = 300)
            }
            var latestIdx = HiPSHqIndex.EMPTY
            val out1 = HiPSDownloadManager.downloadHqTiles(
                backend = backend1,
                missingKeys = targetKeys,
                initialIndex = HiPSHqIndex.EMPTY,
                mirrorBaseUrls = listOf("https://mirror-a.example"),
                callbackDispatcher = Dispatchers.Default,
                onProgress = { _, _, _ -> },
                onBatchCommitted = { idx, _ -> latestIdx = idx }
            )
            assertEquals(29, out1.completedTiles)
            assertFalse(out1.storageFailed)
            assertEquals(29, latestIdx.size)
            assertFalse(latestIdx.containsKey(failingKey))

            // Simulate crash leaving unindexed trailing bytes on dss2_hq.dat before resume
            val hqDataFile = File(File(tempRoot, "hq"), "dss2_hq.dat")
            FileOutputStream(hqDataFile, true).use { it.write(ByteArray(77) { 0x5A }) }
            val reloaded = diskBackend.loadHqIndex()
            assertEquals(29, reloaded.size)

            // Resume fetches only the 1 missing key and truncates the unindexed 77-byte tail cleanly
            val fetchCalls = AtomicInteger(0)
            val expectedRecovered = fakeJpeg(999, size = 340)
            val remaining = HiPSObjectPlanner.filterMissingKeys(targetKeys, false, reloaded)
            assertEquals(1, remaining.size)
            assertEquals(failingKey, remaining[0])

            val backend2 = FakeNetworkBackend(diskBackend) {
                fetchCalls.incrementAndGet()
                expectedRecovered
            }
            val out2 = HiPSDownloadManager.downloadHqTiles(
                backend = backend2,
                missingKeys = remaining,
                initialIndex = reloaded,
                mirrorBaseUrls = listOf("https://mirror-a.example"),
                callbackDispatcher = Dispatchers.Default,
                onProgress = { _, _, _ -> },
                onBatchCommitted = { idx, _ -> latestIdx = idx }
            )
            assertEquals(1, out2.completedTiles)
            assertEquals(1, fetchCalls.get())
            assertEquals(30, latestIdx.size)
            assertEquals(latestIdx.dataEndOffset, diskBackend.hqDataLengthBytes())

            diskBackend.openHqAssetSource().useAssetSource { src ->
                val eIdx = latestIdx.findEntryIndex(failingKey)
                assertTrue(eIdx >= 0)
                assertArrayEquals(
                    expectedRecovered,
                    src.readRange(latestIdx.offsets[eIdx], latestIdx.lengths[eIdx])
                )
            }

            // Base sky pack deletion leaves HQ storage untouched, and vice versa
            assertTrue(diskBackend.writeStagedTile(0, fakeJpeg(1)))
            assertTrue(diskBackend.deleteDownloadedPackAndStaging())
            assertEquals(30, diskBackend.loadHqIndex().size)
            assertTrue(diskBackend.hqStorageSizeBytes() > 0L)

            assertTrue(diskBackend.deleteHqStorage())
            assertEquals(0, diskBackend.loadHqIndex().size)
            assertEquals(0L, diskBackend.hqStorageSizeBytes())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun testHqIndexValidationCancellationAndStorageFailure() = runBlocking {
        // prepareAppend deduplication, empty filtering, all-existing null, and range queries
        val k5 = Healpix.packTileKey(5, 10)
        val k6 = Healpix.packTileKey(6, 40)
        val k7 = Healpix.packTileKey(7, 160)
        val prep1 = HiPSHqIndex.prepareAppend(
            HiPSHqIndex.EMPTY,
            listOf(
                k7 to ByteArray(20) { 3 },
                k5 to ByteArray(10) { 1 },
                k5 to ByteArray(10) { 9 },
                k6 to ByteArray(0)
            )
        )!!
        assertEquals(2, prep1.updatedIndex.size)
        assertNull(HiPSHqIndex.prepareAppend(prep1.updatedIndex, listOf(k5 to ByteArray(10) { 1 })))

        val inRange = mutableListOf<Int>()
        prep1.updatedIndex.forEachKeyInRange(
            Healpix.packTileKey(5, 0),
            Healpix.packTileKey(5, Healpix.tileCountForOrder(5) - 1)
        ) { inRange.add(it) }
        assertEquals(listOf(k5), inRange)

        // Parse rejects corrupt headers, truncated buffers, unsorted keys, bad offsets, and invalid order/ipix
        val validBytes = prep1.indexBytes
        val dataLen = prep1.updatedIndex.dataEndOffset
        assertEquals(2, HiPSHqIndex.parse(validBytes, dataLen).size)
        assertEquals(0, HiPSHqIndex.parse(null, dataLen).size)
        assertEquals(0, HiPSHqIndex.parse(ByteArray(8), dataLen).size)
        assertEquals(0, HiPSHqIndex.parse(HiPSHqIndex.serialize(HiPSHqIndex.EMPTY), 0L).size)
        assertEquals(0, HiPSHqIndex.parse(validBytes.copyOf(validBytes.size - 1), dataLen).size)
        assertEquals(0, HiPSHqIndex.parse(validBytes, dataLen - 1L).size)

        val badMagic = validBytes.copyOf().also { it[0] = 'X'.code.toByte() }
        assertEquals(0, HiPSHqIndex.parse(badMagic, dataLen).size)

        val badVersion = validBytes.copyOf().also { HiPSPackToc.putU32Le(it, 8, 99) }
        assertEquals(0, HiPSHqIndex.parse(badVersion, dataLen).size)

        val dupKeys = validBytes.copyOf().also { HiPSPackToc.putU32Le(it, 32, k5) }
        assertEquals(0, HiPSHqIndex.parse(dupKeys, dataLen).size)

        val badOrder = validBytes.copyOf().also {
            HiPSPackToc.putU32Le(it, 32, Healpix.packTileKey(Healpix.HQ_MAX_ORDER + 1, 0))
        }
        assertEquals(0, HiPSHqIndex.parse(badOrder, dataLen).size)

        val overflowOffset = validBytes.copyOf().also {
            HiPSPackToc.putU64Le(it, 24, Long.MAX_VALUE - 2L)
        }
        assertEquals(0, HiPSHqIndex.parse(overflowOffset, dataLen).size)

        val tempRoot = Files.createTempDirectory("hips_hq_cancel_test").toFile()
        try {
            val diskBackend = AndroidHiPSLocalBackend(tempRoot)

            // Corrupt idx + stale dat recovers cleanly when fresh batch appends at expectedDataEnd = 0L
            val hqDir = File(tempRoot, "hq").apply { mkdirs() }
            File(hqDir, "dss2_hq.dat").writeBytes(ByteArray(500) { 0x33 })
            File(hqDir, "dss2_hq.idx").writeBytes(ByteArray(24) { 0x77 })
            assertEquals(0, diskBackend.loadHqIndex().size)
            assertTrue(diskBackend.appendHqBatch(0L, prep1.payloadsToWrite, validBytes))
            assertEquals(dataLen, diskBackend.hqDataLengthBytes())
            assertEquals(2, diskBackend.loadHqIndex().size)
            assertTrue(diskBackend.deleteHqStorage())

            // Mid-flight cancellation with flushOnExit = true preserves < 25 already-fetched tiles
            val gate = CompletableDeferred<Unit>()
            val queuedSignal1 = CompletableDeferred<Unit>()
            val seen1 = AtomicInteger(0)
            val keys10 = IntArray(10) { i -> Healpix.packTileKey(7, 200 + i) }
            val slowBackend1 = object : HiPSLocalBackend by diskBackend {
                override suspend fun fetchUrlBytes(url: String): ByteArray? {
                    val n = seen1.incrementAndGet()
                    if (n == 1) return fakeJpeg(n, size = 200)
                    gate.await()
                    return fakeJpeg(n, size = 200)
                }
            }
            val job1 = launch(Dispatchers.Default) {
                HiPSDownloadManager.downloadHqTiles(
                    backend = slowBackend1,
                    missingKeys = keys10,
                    initialIndex = HiPSHqIndex.EMPTY,
                    mirrorBaseUrls = listOf("https://mirror-a.example"),
                    callbackDispatcher = Dispatchers.Default,
                    flushOnExit = { true },
                    onProgress = { done, _, _ ->
                        if (done >= 1) queuedSignal1.complete(Unit)
                    },
                    onBatchCommitted = { _, _ -> }
                )
            }
            queuedSignal1.await()
            job1.cancelAndJoin()
            assertEquals(1, diskBackend.loadHqIndex().size)
            assertTrue(diskBackend.deleteHqStorage())

            // Cancellation with flushOnExit = false (used by removeHqImages) writes 0 bytes
            val queuedSignal2 = CompletableDeferred<Unit>()
            val seen2 = AtomicInteger(0)
            val slowBackend2 = object : HiPSLocalBackend by diskBackend {
                override suspend fun fetchUrlBytes(url: String): ByteArray? {
                    val n = seen2.incrementAndGet()
                    if (n == 1) return fakeJpeg(n, size = 200)
                    gate.await()
                    return null
                }
            }
            val job2 = launch(Dispatchers.Default) {
                HiPSDownloadManager.downloadHqTiles(
                    backend = slowBackend2,
                    missingKeys = keys10,
                    initialIndex = HiPSHqIndex.EMPTY,
                    mirrorBaseUrls = listOf("https://mirror-a.example"),
                    callbackDispatcher = Dispatchers.Default,
                    flushOnExit = { false },
                    onProgress = { done, _, _ ->
                        if (done >= 1) queuedSignal2.complete(Unit)
                    },
                    onBatchCommitted = { _, _ -> }
                )
            }
            queuedSignal2.await()
            job2.cancelAndJoin()
            assertEquals(0, diskBackend.loadHqIndex().size)
            assertEquals(0L, diskBackend.hqStorageSizeBytes())

            // Storage write failure sets outcome.storageFailed = true
            val failingWriteBackend = object : HiPSLocalBackend by diskBackend {
                override suspend fun fetchUrlBytes(url: String): ByteArray = fakeJpeg(1, size = 200)
                override fun appendHqBatch(
                    expectedDataEnd: Long,
                    payloads: List<ByteArray>,
                    newIndexBytes: ByteArray
                ): Boolean = false
            }
            val failOut = HiPSDownloadManager.downloadHqTiles(
                backend = failingWriteBackend,
                missingKeys = intArrayOf(k5),
                initialIndex = HiPSHqIndex.EMPTY,
                mirrorBaseUrls = listOf("https://mirror-a.example"),
                callbackDispatcher = Dispatchers.Default,
                onProgress = { _, _, _ -> },
                onBatchCommitted = { _, _ -> }
            )
            assertTrue(failOut.storageFailed)
            assertEquals(0, failOut.completedTiles)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private inline fun <T> HiPSAssetSource.useAssetSource(block: (HiPSAssetSource) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }
}

// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
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
        for (order in Healpix.MIN_ORDER..Healpix.MAX_ORDER) {
            val nside = Healpix.nsideForOrder(order)
            val invNside = 1.0 / nside.toDouble()
            val npix = Healpix.tileCountForOrder(order)
            val step = when {
                order <= 2 -> 1
                order == 3 -> 7
                else -> 29
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
}

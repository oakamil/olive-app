// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Platform-specific random-access reader and image decoder for a local HiPS `.pack` file.
 */
interface HiPSAssetSource {
    /** Reads [length] bytes starting at [offset] from the active `.pack` file, or null if unavailable. */
    fun readRange(offset: Long, length: Int): ByteArray?

    /** Decodes compressed tile bytes into a GPU-friendly [ImageBitmap]. */
    fun decodeTile(bytes: ByteArray): ImageBitmap?

    /** Releases underlying native/OS file descriptors when closed. */
    fun close() {}
}

/** Snapshot of staged `.jpg` tiles currently saved on disk for download resume. */
data class HiPSStagedStats(
    val flags: BooleanArray,
    val totalBytes: Long
)

/**
 * Platform storage and HTTP primitives backing on-demand download, resume staging,
 * and atomic `OLVHIPS1` `.pack` assembly of sky background tiles.
 */
interface HiPSLocalBackend {
    /** Returns the assembled local `.pack` file size in bytes if it exists and passes header/TOC bounds validation, or `0L` otherwise. */
    fun downloadedPackSizeBytes(): Long

    /** Opens a read-only [HiPSAssetSource] backed by the assembled local `.pack` file. */
    fun openDownloadedAssetSource(): HiPSAssetSource

    /** Single-pass scan returning staged presence flags and total bytes. */
    fun getStagedStats(): HiPSStagedStats

    /** Atomically writes a validated tile payload for [globalIdx] into the staging directory. */
    fun writeStagedTile(globalIdx: Int, bytes: ByteArray): Boolean

    /**
     * Streams the `OLVHIPS1` header, 16-byte TOC entries, and all staged tiles into
     * a temporary `.pack`, swaps it atomically into place, and cleans up staging files.
     */
    fun assemblePackFromStaging(onTilePacked: (Int) -> Unit): Boolean

    /** Deletes any assembled local `.pack` file and staged tiles; returns true if both pack and staging are absent afterward. */
    fun deleteDownloadedPackAndStaging(): Boolean

    /** Performs an HTTPS GET request and returns body bytes on HTTP `200`, or `null` otherwise. */
    suspend fun fetchUrlBytes(url: String): ByteArray?

    /** Byte length of `hq/dss2_hq.dat`, or `0L` if absent. */
    fun hqDataLengthBytes(): Long

    /** Total on-disk bytes occupied by `hq/` (`dss2_hq.dat` + `dss2_hq.idx`), or `0L` if empty. */
    fun hqStorageSizeBytes(): Long

    /** Reads raw bytes of `hq/dss2_hq.idx`, or `null` if absent. */
    fun readHqIndexBytes(): ByteArray?

    /** Parses and validates the persisted [HiPSHqIndex] against `dss2_hq.dat`. */
    fun loadHqIndex(): HiPSHqIndex = HiPSHqIndex.parse(readHqIndexBytes(), hqDataLengthBytes())

    /** Opens a read-only [HiPSAssetSource] backed by `hq/dss2_hq.dat`. */
    fun openHqAssetSource(): HiPSAssetSource

    /**
     * Appends [payloads] at [expectedDataEnd] in `hq/dss2_hq.dat` (truncating any unindexed tail),
     * `fsync`s the data file, and atomically replaces `hq/dss2_hq.idx` with [newIndexBytes].
     */
    fun appendHqBatch(
        expectedDataEnd: Long,
        payloads: List<ByteArray>,
        newIndexBytes: ByteArray
    ): Boolean

    /** Deletes only the `hq/` directory, leaving the base `O0–O4` sky pack untouched. */
    fun deleteHqStorage(): Boolean

    companion object {
        private const val PACK_FILE_PREFIX = "dss2_o0_"
        val PACK_FILE_NAME = "${PACK_FILE_PREFIX}${Healpix.MAX_ORDER}.pack"
        val TEMP_PACK_NAME = "$PACK_FILE_NAME.tmp"
        const val STAGING_DIR_NAME = "dss2_staging"
        const val HQ_DIR_NAME = "hq"
        const val HQ_DATA_FILE_NAME = "dss2_hq.dat"
        const val HQ_INDEX_FILE_NAME = "dss2_hq.idx"
        const val HQ_TEMP_INDEX_NAME = "dss2_hq.idx.tmp"
        const val USER_AGENT = "OliveApp/1.0 (DSS2-HiPS)"

        fun isPackOrTempFileName(name: String): Boolean =
            name.startsWith(PACK_FILE_PREFIX) && (name.endsWith(".pack") || name.endsWith(".pack.tmp"))

        fun parseStagedTileIndex(name: String): Int {
            if (!name.endsWith(".jpg")) return -1
            val idx = name.removeSuffix(".jpg").toIntOrNull() ?: return -1
            return if (idx in 0 until Healpix.TOTAL_TILES) idx else -1
        }
    }
}

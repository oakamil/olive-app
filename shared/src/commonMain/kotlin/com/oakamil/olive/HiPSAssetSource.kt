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

    companion object {
        private const val PACK_FILE_PREFIX = "dss2_o0_"
        val PACK_FILE_NAME = "${PACK_FILE_PREFIX}${Healpix.MAX_ORDER}.pack"
        val TEMP_PACK_NAME = "$PACK_FILE_NAME.tmp"
        const val STAGING_DIR_NAME = "dss2_staging"
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

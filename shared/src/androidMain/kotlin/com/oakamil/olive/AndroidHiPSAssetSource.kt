// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Thread-safe positional file-backed [HiPSAssetSource] reading the downloaded `OLVHIPS1` pack
 * from internal app storage (`filesDir/hips`).
 */
class AndroidHiPSAssetSource(
    private val packFile: File
) : HiPSAssetSource {

    private val lock = Any()
    private val rgbaLock = Any()
    private var rgbaBuffer: ByteArray? = null
    private var raf: RandomAccessFile? = null
    private var channel: FileChannel? = null
    private var closed = false

    private fun ensureChannelLocked(): FileChannel? {
        if (closed) return null
        channel?.let { if (it.isOpen) return it }
        if (!packFile.isFile) return null
        return try {
            val openedRaf = RandomAccessFile(packFile, "r")
            raf = openedRaf
            openedRaf.channel.also { channel = it }
        } catch (e: Exception) {
            Log.w("HiPS", "Failed opening ${packFile.absolutePath}", e)
            null
        }
    }

    override fun readRange(offset: Long, length: Int): ByteArray? {
        if (offset < 0L || length <= 0) return null
        val ch = synchronized(lock) { ensureChannelLocked() } ?: return null
        return try {
            val out = ByteArray(length)
            val buf = ByteBuffer.wrap(out)
            var totalRead = 0
            while (buf.hasRemaining()) {
                val n = ch.read(buf, offset + totalRead)
                if (n <= 0) break
                totalRead += n
            }
            if (totalRead == length) out else null
        } catch (e: Exception) {
            Log.w("HiPS", "readRange($offset, $length) failed", e)
            null
        }
    }

    override fun decodeTile(bytes: ByteArray): ImageBitmap? {
        if (bytes.isEmpty()) return null
        return try {
            val opts = BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            val bmp = if (decoded.isMutable && decoded.config == Bitmap.Config.ARGB_8888) {
                decoded
            } else {
                val copy =
                    decoded.copy(Bitmap.Config.ARGB_8888, true) ?: return decoded.asImageBitmap()
                decoded.recycle()
                copy
            }
            if (bmp.width < HiPSTileNormalizer.MIN_DIM || bmp.height < HiPSTileNormalizer.MIN_DIM) {
                return bmp.asImageBitmap()
            }
            val byteCountLong = bmp.width.toLong() * bmp.height.toLong() * 4L
            if (byteCountLong > Int.MAX_VALUE.toLong()) return bmp.asImageBitmap()
            val byteCount = byteCountLong.toInt()
            synchronized(rgbaLock) {
                val existing = rgbaBuffer
                val rgba = if (existing != null && existing.size >= byteCount) {
                    existing
                } else {
                    ByteArray(byteCount).also { rgbaBuffer = it }
                }
                val buf = ByteBuffer.wrap(rgba, 0, byteCount)
                bmp.copyPixelsToBuffer(buf)
                HiPSTileNormalizer.normalizeRgba(rgba, bmp.width, bmp.height)
                buf.rewind()
                bmp.copyPixelsFromBuffer(buf)
            }
            bmp.asImageBitmap()
        } catch (e: Exception) {
            Log.w("HiPS", "Failed decoding tile on Android", e)
            null
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            try {
                channel?.close()
            } catch (_: Exception) {
            }
            try {
                raf?.close()
            } catch (_: Exception) {
            }
            channel = null
            raf = null
        }
        synchronized(rgbaLock) {
            rgbaBuffer = null
        }
    }
}

/**
 * Android [HiPSLocalBackend] managing persistent `filesDir/hips` pack storage,
 * resumable tile staging (`dss2_staging/<idx>.jpg`), atomic pack assembly, and pooled HTTPS fetches.
 */
class AndroidHiPSLocalBackend(
    private val hipsDir: File
) : HiPSLocalBackend {

    constructor(context: Context) : this(File(context.filesDir, "hips"))

    companion object {
        init {
            try {
                System.setProperty("http.maxConnections", "12")
            } catch (_: Exception) {
            }
        }
    }

    private val packFile = File(hipsDir, HiPSLocalBackend.PACK_FILE_NAME)
    private val packTempFile = File(hipsDir, HiPSLocalBackend.TEMP_PACK_NAME)
    private val stagingDir = File(hipsDir, HiPSLocalBackend.STAGING_DIR_NAME)

    private fun stagedFile(globalIdx: Int): File = File(stagingDir, "$globalIdx.jpg")

    override fun downloadedPackSizeBytes(): Long {
        if (!packFile.isFile) return 0L
        val fileLen = packFile.length()
        return if (HiPSPackToc.validatePack(
                AndroidHiPSAssetSource(packFile),
                fileLen
            )
        ) fileLen else 0L
    }

    override fun openDownloadedAssetSource(): HiPSAssetSource = AndroidHiPSAssetSource(packFile)

    override fun getStagedStats(): HiPSStagedStats {
        val flags = BooleanArray(Healpix.TOTAL_TILES)
        val files = stagingDir.listFiles() ?: return HiPSStagedStats(flags, 0L)
        var totalBytes = 0L
        for (f in files) {
            val idx = HiPSLocalBackend.parseStagedTileIndex(f.name)
            if (idx < 0 || !f.isFile) continue
            val len = f.length()
            if (len > 0L) {
                flags[idx] = true
                totalBytes += len
            }
        }
        return HiPSStagedStats(flags, totalBytes)
    }

    override fun writeStagedTile(globalIdx: Int, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val partFile = File(stagingDir, "$globalIdx.part")
        var ok = false
        return try {
            if (!stagingDir.exists()) stagingDir.mkdirs()
            val finalFile = stagedFile(globalIdx)
            FileOutputStream(partFile).use { out ->
                out.write(bytes)
                out.flush()
            }
            if (!partFile.renameTo(finalFile)) {
                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) return false
            }
            ok = true
            true
        } catch (e: Exception) {
            Log.w("HiPS", "Failed writing staged tile $globalIdx", e)
            false
        } finally {
            if (!ok && partFile.exists()) {
                partFile.delete()
            }
        }
    }

    override fun assemblePackFromStaging(onTilePacked: (Int) -> Unit): Boolean {
        val tileCount = Healpix.TOTAL_TILES
        val headerBytes = HiPSPackToc.buildHeaderBytes()
        return try {
            if (!hipsDir.exists()) hipsDir.mkdirs()
            if (packTempFile.exists()) packTempFile.delete()

            val tocBytesLen = tileCount.toLong() * HiPSPackToc.TOC_ENTRY_BYTES
            val dataStart = HiPSPackToc.HEADER_BYTES.toLong() + tocBytesLen
            val offsets = LongArray(tileCount)
            val lengths = IntArray(tileCount)
            var currentOffset = dataStart
            val buf = ByteArray(65536)
            var assembled = false

            try {
                RandomAccessFile(packTempFile, "rw").use { raf ->
                    // Reserve header + TOC space up front so payloads stream linearly
                    raf.setLength(dataStart)
                    raf.seek(dataStart)

                    for (i in 0 until tileCount) {
                        val src = stagedFile(i)
                        if (!src.isFile) return false
                        val lenLong = src.length()
                        if (lenLong <= 0L || lenLong > Int.MAX_VALUE.toLong()) return false
                        var copied = 0
                        FileInputStream(src).use { input ->
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                raf.write(buf, 0, n)
                                copied += n
                            }
                        }
                        if (copied.toLong() != lenLong) return false
                        offsets[i] = currentOffset
                        lengths[i] = copied
                        currentOffset += copied.toLong()
                        onTilePacked(i + 1)
                    }

                    val tocBytes = HiPSPackToc.buildTocBytes(offsets, lengths)
                    raf.seek(0L)
                    raf.write(headerBytes)
                    raf.write(tocBytes)
                    raf.fd.sync()
                    assembled = true
                }
            } finally {
                if (!assembled && packTempFile.exists()) {
                    packTempFile.delete()
                }
            }

            if (!packTempFile.renameTo(packFile)) {
                if (packFile.exists()) packFile.delete()
                if (!packTempFile.renameTo(packFile)) {
                    packTempFile.delete()
                    return false
                }
            }
            stagingDir.deleteRecursively()
            true
        } catch (e: Exception) {
            Log.w("HiPS", "Failed assembling pack on Android", e)
            packTempFile.delete()
            false
        }
    }

    override fun deleteDownloadedPackAndStaging(): Boolean {
        return try {
            hipsDir.listFiles()?.forEach { f ->
                if (f.isFile && HiPSLocalBackend.isPackOrTempFileName(f.name)) {
                    f.delete()
                }
            }
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            !packFile.exists() && !packTempFile.exists() && !stagingDir.exists()
        } catch (e: Exception) {
            Log.w("HiPS", "Failed deleting HiPS pack", e)
            false
        }
    }

    override suspend fun fetchUrlBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", HiPSLocalBackend.USER_AGENT)
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                try {
                    connection.errorStream?.close()
                } catch (_: Exception) {
                }
                connection.disconnect()
                return@withContext null
            }
            BufferedInputStream(connection.inputStream, 32768).use { stream ->
                stream.readBytes()
            }
        } catch (e: CancellationException) {
            connection?.disconnect()
            throw e
        } catch (_: Exception) {
            connection?.disconnect()
            null
        }
    }
}

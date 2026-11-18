// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.impl.use
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSLock
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setValue
import platform.posix.EINTR
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.SEEK_SET
import platform.posix.errno
import platform.posix.fsync
import platform.posix.lseek
import platform.posix.memcpy
import platform.posix.pread
import kotlin.coroutines.resume
import platform.posix.close as posixClose
import platform.posix.open as posixOpen
import platform.posix.read as posixRead
import platform.posix.rename as posixRename
import platform.posix.write as posixWrite

/**
 * Positional POSIX `pread` [HiPSAssetSource] reading the downloaded `OLVHIPS1` pack from
 * `Application Support/Olive/hips`.
 */
@OptIn(ExperimentalForeignApi::class)
class IosHiPSAssetSource(
    private val packPath: String
) : HiPSAssetSource {

    private val lock = NSLock()
    private var fd: Int = -1
    private var closed = false

    private fun ensureOpenLocked(): Int {
        if (closed) return -1
        if (fd >= 0) return fd
        if (!NSFileManager.defaultManager.fileExistsAtPath(packPath)) return -1
        val opened = posixOpen(packPath, O_RDONLY)
        if (opened < 0) {
            Log.w("HiPS", "Failed to open $packPath")
            return -1
        }
        fd = opened
        return fd
    }

    override fun readRange(offset: Long, length: Int): ByteArray? {
        if (offset < 0L || length <= 0) return null
        lock.lock()
        return try {
            val activeFd = ensureOpenLocked()
            if (activeFd < 0) return null
            readPreadExact(activeFd, offset, length)
        } finally {
            lock.unlock()
        }
    }

    override fun decodeTile(bytes: ByteArray): ImageBitmap? {
        if (bytes.isEmpty()) return null
        return try {
            Image.makeFromEncoded(bytes).use { skImage ->
                val w = skImage.width
                val h = skImage.height
                if (w <= 0 || h <= 0) return null
                if (w < HiPSTileNormalizer.MIN_DIM || h < HiPSTileNormalizer.MIN_DIM) {
                    return skImage.toComposeImageBitmap()
                }
                val rowBytesLong = w.toLong() * 4L
                if (rowBytesLong * h.toLong() > Int.MAX_VALUE.toLong()) {
                    return skImage.toComposeImageBitmap()
                }
                val rowBytes = rowBytesLong.toInt()
                val alphaType =
                    if (skImage.isOpaque) ColorAlphaType.OPAQUE else ColorAlphaType.UNPREMUL
                val info = ImageInfo(w, h, ColorType.RGBA_8888, alphaType)
                val pixels = Bitmap().use { tempBmp ->
                    if (!tempBmp.allocPixels(info, rowBytes) || !skImage.readPixels(tempBmp)) {
                        return skImage.toComposeImageBitmap()
                    }
                    tempBmp.readPixels(info, rowBytes) ?: return skImage.toComposeImageBitmap()
                }
                HiPSTileNormalizer.normalizeRgba(pixels, w, h)
                val skiaBitmap = Bitmap()
                if (!skiaBitmap.installPixels(info, pixels, rowBytes)) {
                    return skImage.toComposeImageBitmap()
                }
                skiaBitmap.setImmutable()
                skiaBitmap.asComposeImageBitmap()
            }
        } catch (e: Exception) {
            Log.w("HiPS", "Failed decoding tile on iOS", e)
            null
        }
    }

    override fun close() {
        lock.lock()
        try {
            if (!closed) {
                closed = true
                if (fd >= 0) {
                    posixClose(fd)
                    fd = -1
                }
            }
        } finally {
            lock.unlock()
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readPreadExact(fd: Int, offset: Long, length: Int): ByteArray? {
    val out = ByteArray(length)
    var totalRead = 0
    out.usePinned { pinned ->
        while (totalRead < length) {
            val n = pread(
                fd,
                pinned.addressOf(totalRead),
                (length - totalRead).convert(),
                offset + totalRead
            )
            if (n < 0L) {
                if (errno == EINTR) continue
                break
            }
            if (n == 0L) break
            totalRead += n.toInt()
        }
    }
    return if (totalRead == length) out else null
}

/**
 * iOS [HiPSLocalBackend] storing the downloaded DSS2 pack in `Application Support/Olive/hips/`
 * with `NSURLIsExcludedFromBackupKey = true`, POSIX atomic staging & assembly, and pooled `NSURLSession`.
 */
@OptIn(ExperimentalForeignApi::class)
class IosHiPSLocalBackend : HiPSLocalBackend {

    companion object {
        private const val FILE_MODE_0644 = 420 // 0644 octal
    }

    private val session: NSURLSession by lazy {
        val config = NSURLSessionConfiguration.defaultSessionConfiguration.apply {
            HTTPMaximumConnectionsPerHost = 6
            timeoutIntervalForRequest = 15.0
            timeoutIntervalForResource = 30.0
        }
        NSURLSession.sessionWithConfiguration(config)
    }

    private fun resolveHipsDir(): String? {
        val fm = NSFileManager.defaultManager
        val urls = fm.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask)
        val appSupportUrl = urls.firstOrNull() as? NSURL ?: return null
        val appSupportPath = appSupportUrl.path ?: return null
        return "$appSupportPath/Olive/hips"
    }

    private fun ensureDirExistsAndExcluded(dirPath: String): Boolean {
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(dirPath)) {
            if (!fm.createDirectoryAtPath(
                    path = dirPath,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null
                )
            ) {
                return false
            }
        }
        excludeFromBackup(dirPath)
        return true
    }

    private fun excludeFromBackup(path: String) {
        NSURL.fileURLWithPath(path).setResourceValue(
            value = true,
            forKey = NSURLIsExcludedFromBackupKey,
            error = null
        )
    }

    private fun fileSizeBytes(path: String): Long {
        val attrs =
            NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) ?: return 0L
        val sizeNum = attrs[NSFileSize] as? NSNumber ?: return 0L
        return sizeNum.longLongValue
    }

    override fun downloadedPackSizeBytes(): Long {
        val hipsDir = resolveHipsDir() ?: return 0L
        val packPath = "$hipsDir/${HiPSLocalBackend.PACK_FILE_NAME}"
        if (!NSFileManager.defaultManager.fileExistsAtPath(packPath)) return 0L
        val fileLen = fileSizeBytes(packPath)
        return if (HiPSPackToc.validatePack(IosHiPSAssetSource(packPath), fileLen)) fileLen else 0L
    }

    override fun openDownloadedAssetSource(): HiPSAssetSource {
        val path = resolveHipsDir()?.let { "$it/${HiPSLocalBackend.PACK_FILE_NAME}" } ?: ""
        return IosHiPSAssetSource(path)
    }

    override fun getStagedStats(): HiPSStagedStats {
        val flags = BooleanArray(Healpix.TOTAL_TILES)
        val hipsDir = resolveHipsDir() ?: return HiPSStagedStats(flags, 0L)
        val stagingDir = "$hipsDir/${HiPSLocalBackend.STAGING_DIR_NAME}"
        val entries =
            NSFileManager.defaultManager.contentsOfDirectoryAtPath(stagingDir, error = null)
                ?: return HiPSStagedStats(flags, 0L)
        var totalBytes = 0L
        for (entry in entries) {
            val name = entry as? String ?: continue
            val idx = HiPSLocalBackend.parseStagedTileIndex(name)
            if (idx < 0) continue
            val len = fileSizeBytes("$stagingDir/$name")
            if (len > 0L) {
                flags[idx] = true
                totalBytes += len
            }
        }
        return HiPSStagedStats(flags, totalBytes)
    }

    private fun writeAll(fd: Int, bytes: ByteArray, length: Int = bytes.size): Boolean {
        var written = 0
        bytes.usePinned { pinned ->
            while (written < length) {
                val n = posixWrite(fd, pinned.addressOf(written), (length - written).convert())
                if (n < 0L) {
                    if (errno == EINTR) continue
                    return false
                }
                if (n == 0L) return false
                written += n.toInt()
            }
        }
        return true
    }

    override fun writeStagedTile(globalIdx: Int, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val hipsDir = resolveHipsDir() ?: return false
        if (!ensureDirExistsAndExcluded(hipsDir)) return false
        val stagingDir = "$hipsDir/${HiPSLocalBackend.STAGING_DIR_NAME}"
        if (!ensureDirExistsAndExcluded(stagingDir)) return false

        val partPath = "$stagingDir/$globalIdx.part"
        val finalPath = "$stagingDir/$globalIdx.jpg"
        val fd = posixOpen(partPath, O_WRONLY or O_CREAT or O_TRUNC, FILE_MODE_0644)
        if (fd < 0) return false
        val ok = try {
            writeAll(fd, bytes)
        } finally {
            posixClose(fd)
        }
        if (!ok) {
            NSFileManager.defaultManager.removeItemAtPath(partPath, null)
            return false
        }
        if (posixRename(partPath, finalPath) != 0) {
            NSFileManager.defaultManager.removeItemAtPath(partPath, null)
            return false
        }
        return true
    }

    override fun assemblePackFromStaging(onTilePacked: (Int) -> Unit): Boolean {
        val tileCount = Healpix.TOTAL_TILES
        val headerBytes = HiPSPackToc.buildHeaderBytes()
        val hipsDir = resolveHipsDir() ?: return false
        if (!ensureDirExistsAndExcluded(hipsDir)) return false
        val stagingDir = "$hipsDir/${HiPSLocalBackend.STAGING_DIR_NAME}"
        val packPath = "$hipsDir/${HiPSLocalBackend.PACK_FILE_NAME}"
        val tempPath = "$hipsDir/${HiPSLocalBackend.TEMP_PACK_NAME}"
        val fm = NSFileManager.defaultManager
        if (fm.fileExistsAtPath(tempPath)) fm.removeItemAtPath(tempPath, null)

        val outFd = posixOpen(tempPath, O_WRONLY or O_CREAT or O_TRUNC, FILE_MODE_0644)
        if (outFd < 0) return false

        var success = false
        try {
            val tocBytesLen = tileCount.toLong() * HiPSPackToc.TOC_ENTRY_BYTES
            val dataStart = HiPSPackToc.HEADER_BYTES.toLong() + tocBytesLen
            val placeholder = ByteArray(dataStart.toInt())
            if (!writeAll(outFd, placeholder)) return false

            val offsets = LongArray(tileCount)
            val lengths = IntArray(tileCount)
            var currentOffset = dataStart
            val buf = ByteArray(65536)

            for (i in 0 until tileCount) {
                val srcPath = "$stagingDir/$i.jpg"
                val srcFd = posixOpen(srcPath, O_RDONLY)
                if (srcFd < 0) return false
                var copied = 0
                try {
                    buf.usePinned { pinned ->
                        while (true) {
                            val nRead = posixRead(srcFd, pinned.addressOf(0), buf.size.convert())
                            if (nRead < 0L) {
                                if (errno == EINTR) continue
                                return false
                            }
                            if (nRead == 0L) break
                            val n = nRead.toInt()
                            if (!writeAll(outFd, buf, n)) return false
                            copied += n
                        }
                    }
                } finally {
                    posixClose(srcFd)
                }
                if (copied <= 0) return false
                offsets[i] = currentOffset
                lengths[i] = copied
                currentOffset += copied.toLong()
                onTilePacked(i + 1)
            }

            if (lseek(outFd, 0L, SEEK_SET) != 0L) return false
            val tocBytes = HiPSPackToc.buildTocBytes(offsets, lengths)
            if (!writeAll(outFd, headerBytes)) return false
            if (!writeAll(outFd, tocBytes)) return false
            fsync(outFd)
            success = true
        } finally {
            posixClose(outFd)
            if (!success) {
                fm.removeItemAtPath(tempPath, null)
            }
        }

        excludeFromBackup(tempPath)
        val swapped = if (fm.fileExistsAtPath(packPath)) {
            fm.replaceItemAtURL(
                originalItemURL = NSURL.fileURLWithPath(packPath),
                withItemAtURL = NSURL.fileURLWithPath(tempPath),
                backupItemName = null,
                options = 0uL,
                resultingItemURL = null,
                error = null
            )
        } else {
            fm.moveItemAtPath(tempPath, packPath, error = null)
        }
        if (!swapped) {
            fm.removeItemAtPath(tempPath, null)
            return false
        }
        excludeFromBackup(packPath)
        fm.removeItemAtPath(stagingDir, null)
        return true
    }

    override fun deleteDownloadedPackAndStaging(): Boolean {
        val hipsDir = resolveHipsDir() ?: return false
        val fm = NSFileManager.defaultManager
        val entries = fm.contentsOfDirectoryAtPath(hipsDir, error = null)
        if (entries != null) {
            for (entry in entries) {
                val name = entry as? String ?: continue
                if (HiPSLocalBackend.isPackOrTempFileName(name)) {
                    fm.removeItemAtPath("$hipsDir/$name", null)
                }
            }
        }
        val stagingPath = "$hipsDir/${HiPSLocalBackend.STAGING_DIR_NAME}"
        if (fm.fileExistsAtPath(stagingPath)) {
            fm.removeItemAtPath(stagingPath, null)
        }
        return !fm.fileExistsAtPath("$hipsDir/${HiPSLocalBackend.PACK_FILE_NAME}") &&
                !fm.fileExistsAtPath("$hipsDir/${HiPSLocalBackend.TEMP_PACK_NAME}") &&
                !fm.fileExistsAtPath(stagingPath)
    }

    override suspend fun fetchUrlBytes(url: String): ByteArray? {
        val nsUrl = NSURL.URLWithString(url) ?: return null
        val request = NSMutableURLRequest.requestWithURL(nsUrl).apply {
            setTimeoutInterval(15.0)
            setValue(HiPSLocalBackend.USER_AGENT, forHTTPHeaderField = "User-Agent")
        }
        return suspendCancellableCoroutine { cont ->
            val task = session.dataTaskWithRequest(request) { data, response, error ->
                if (!cont.isActive) return@dataTaskWithRequest
                if (error != null) {
                    cont.resume(null)
                    return@dataTaskWithRequest
                }
                val httpResp = response as? NSHTTPURLResponse
                val code = httpResp?.statusCode ?: 0L
                if (code !in 200L..299L || data == null) {
                    cont.resume(null)
                    return@dataTaskWithRequest
                }
                val len = data.length.toInt()
                if (len <= 0) {
                    cont.resume(ByteArray(0))
                    return@dataTaskWithRequest
                }
                val ptr = data.bytes
                if (ptr == null) {
                    cont.resume(null)
                    return@dataTaskWithRequest
                }
                val bytes = ByteArray(len)
                bytes.usePinned { pinned ->
                    memcpy(pinned.addressOf(0), ptr, data.length)
                }
                cont.resume(bytes)
            }
            cont.invokeOnCancellation {
                task.cancel()
            }
            task.resume()
        }
    }
}

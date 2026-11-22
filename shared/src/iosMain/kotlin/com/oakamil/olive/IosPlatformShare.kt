// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController
import platform.UniformTypeIdentifiers.UTTypeCommaSeparatedText
import platform.UniformTypeIdentifiers.UTTypePlainText
import platform.darwin.NSObject
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fwrite

private const val TAG = "IosPlatformShare"

private fun findTopViewController(): UIViewController? {
    val scenes = UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>()
    val activeScene = scenes.firstOrNull {
        it.activationState == UISceneActivationStateForegroundActive
    } ?: scenes.firstOrNull()
    val keyWindow = activeScene?.windows
        ?.filterIsInstance<UIWindow>()
        ?.firstOrNull { it.isKeyWindow() }
        ?: activeScene?.windows?.filterIsInstance<UIWindow>()?.firstOrNull()
    var top = keyWindow?.rootViewController ?: return null
    while (true) {
        top = top.presentedViewController ?: break
    }
    return top
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberFileSharer(): suspend (fileName: String, mimeType: String, content: String) -> Unit {
    return remember {
        { fileName, _, content ->
            val safeName = fileName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .takeUnless { it.isBlank() || it == "." || it == ".." }
                ?: "export.csv"
            val tempDir = NSTemporaryDirectory().trimEnd('/')
            val exportDirPath = "$tempDir/olive_export_${NSUUID().UUIDString}"
            val filePath = "$exportDirPath/$safeName"
            val written = try {
                withContext(Dispatchers.IO) {
                    val fm = NSFileManager.defaultManager
                    val dirCreated = fm.createDirectoryAtPath(
                        path = exportDirPath,
                        withIntermediateDirectories = true,
                        attributes = null,
                        error = null
                    )
                    if (!dirCreated) {
                        Log.e(TAG, "Failed to create export temp directory for $safeName")
                        return@withContext false
                    }
                    val bytes = content.encodeToByteArray()
                    val fp = fopen(filePath, "wb")
                    if (fp == null) {
                        Log.e(TAG, "Failed to open temp file for $safeName")
                        fm.removeItemAtPath(exportDirPath, null)
                        return@withContext false
                    }
                    var writeOk = true
                    try {
                        if (bytes.isNotEmpty()) {
                            val count = bytes.usePinned { pinned ->
                                fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), fp)
                            }
                            writeOk = count == bytes.size.toULong()
                        }
                    } finally {
                        fclose(fp)
                    }
                    if (!writeOk) {
                        Log.e(TAG, "Failed to write complete export file for $safeName")
                        fm.removeItemAtPath(exportDirPath, null)
                    }
                    writeOk
                }
            } catch (e: CancellationException) {
                NSFileManager.defaultManager.removeItemAtPath(exportDirPath, null)
                throw e
            }
            if (written) {
                val presenter = findTopViewController()
                if (presenter == null || presenter.isBeingDismissed()) {
                    Log.e(TAG, "No active presenter available to share $safeName")
                    NSFileManager.defaultManager.removeItemAtPath(exportDirPath, null)
                } else {
                    val fileUrl = NSURL.fileURLWithPath(filePath)
                    val activityController = UIActivityViewController(
                        activityItems = listOf(fileUrl),
                        applicationActivities = null
                    )
                    activityController.completionWithItemsHandler = { _, _, _, _ ->
                        NSFileManager.defaultManager.removeItemAtPath(exportDirPath, null)
                    }
                    activityController.popoverPresentationController?.let { popover ->
                        val view = presenter.view
                        popover.sourceView = view
                        val (midX, midY) = view.bounds.useContents {
                            (size.width / 2.0) to (size.height / 2.0)
                        }
                        popover.sourceRect = CGRectMake(midX, midY, 1.0, 1.0)
                        popover.permittedArrowDirections = 0u
                    }
                    presenter.presentViewController(
                        activityController,
                        animated = true,
                        completion = null
                    )
                }
            }
        }
    }
}

private class IosCsvPickerDelegateHolder {
    var activeDelegate: UIDocumentPickerDelegateProtocol? = null
}

@OptIn(ExperimentalForeignApi::class)
private fun readFileBoundedUtf8(filePath: String): Any? {
    val fp = fopen(filePath, "rb") ?: return null
    val chunks = mutableListOf<ByteArray>()
    var total = 0
    try {
        val chunkSize = 8192
        while (true) {
            val buf = ByteArray(chunkSize)
            val readCount = buf.usePinned { pinned ->
                fread(pinned.addressOf(0), 1u, chunkSize.toULong(), fp)
            }.toInt()
            if (readCount <= 0) break
            total += readCount
            if (total > MAX_CSV_IMPORT_BYTES) {
                return FilePickError.TOO_LARGE
            }
            chunks.add(if (readCount == chunkSize) buf else buf.copyOf(readCount))
        }
    } finally {
        fclose(fp)
    }
    val combined = ByteArray(total)
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(combined, destinationOffset = offset)
        offset += chunk.size
    }
    return combined.decodeToString()
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberCsvFilePicker(
    onFilePicked: (String) -> Unit,
    onError: (FilePickError) -> Unit
): () -> Unit {
    val coroutineScope = rememberCoroutineScope()
    val currentOnFilePicked by rememberUpdatedState(onFilePicked)
    val currentOnError by rememberUpdatedState(onError)
    val delegateHolder = remember { IosCsvPickerDelegateHolder() }

    DisposableEffect(delegateHolder) {
        onDispose {
            delegateHolder.activeDelegate = null
        }
    }

    return remember(coroutineScope, delegateHolder) {
        openPicker@{
            if (delegateHolder.activeDelegate != null) return@openPicker
            val presenter = findTopViewController()
            if (presenter == null ||
                presenter.isBeingDismissed() ||
                presenter.isBeingPresented() ||
                presenter.presentedViewController != null
            ) {
                Log.e(TAG, "No active presenter available to open CSV picker")
                currentOnError(FilePickError.READ_FAILED)
                return@openPicker
            }
            val picker = UIDocumentPickerViewController(
                forOpeningContentTypes = listOf(UTTypeCommaSeparatedText, UTTypePlainText),
                asCopy = true
            )
            picker.allowsMultipleSelection = false
            val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
                override fun documentPicker(
                    controller: UIDocumentPickerViewController,
                    didPickDocumentsAtURLs: List<*>
                ) {
                    delegateHolder.activeDelegate = null
                    val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
                    val filePath = url?.path
                    if (filePath == null) {
                        currentOnError(FilePickError.READ_FAILED)
                        return
                    }
                    coroutineScope.launch {
                        try {
                            val readResult = withContext(Dispatchers.IO) {
                                try {
                                    readFileBoundedUtf8(filePath)
                                } finally {
                                    NSFileManager.defaultManager.removeItemAtPath(
                                        filePath,
                                        null
                                    )
                                }
                            }
                            when (readResult) {
                                is String -> currentOnFilePicked(readResult)
                                is FilePickError -> currentOnError(readResult)
                                else -> currentOnError(FilePickError.READ_FAILED)
                            }
                        } catch (e: CancellationException) {
                            NSFileManager.defaultManager.removeItemAtPath(filePath, null)
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to read imported CSV: ${e.message}")
                            currentOnError(FilePickError.READ_FAILED)
                        }
                    }
                }

                override fun documentPickerWasCancelled(
                    controller: UIDocumentPickerViewController
                ) {
                    delegateHolder.activeDelegate = null
                }
            }
            delegateHolder.activeDelegate = delegate
            picker.delegate = delegate
            presenter.presentViewController(picker, animated = true, completion = null)
        }
    }
}

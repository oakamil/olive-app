// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

@OptIn(ExperimentalForeignApi::class)
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
actual fun rememberFileSharer(): (fileName: String, mimeType: String, content: String) -> Unit {
    return remember {
        { fileName, _, content ->
            val tempDir = NSTemporaryDirectory()
            val filePath = if (tempDir.endsWith("/")) "$tempDir$fileName" else "$tempDir/$fileName"
            val bytes = content.encodeToByteArray()
            val fp = fopen(filePath, "wb")
            if (fp != null) {
                var writeOk = true
                try {
                    if (bytes.isNotEmpty()) {
                        val written = bytes.usePinned { pinned ->
                            fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), fp)
                        }
                        writeOk = written == bytes.size.toULong()
                    }
                } finally {
                    fclose(fp)
                }
                if (!writeOk) {
                    NSFileManager.defaultManager.removeItemAtPath(filePath, null)
                } else {
                    val fileUrl = NSURL.fileURLWithPath(filePath)
                    val activityController = UIActivityViewController(
                        activityItems = listOf(fileUrl),
                        applicationActivities = null
                    )
                    activityController.completionWithItemsHandler = { _, _, _, _ ->
                        NSFileManager.defaultManager.removeItemAtPath(filePath, null)
                    }
                    val presenter = findTopViewController()
                    if (presenter != null) {
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
                    } else {
                        NSFileManager.defaultManager.removeItemAtPath(filePath, null)
                    }
                }
            }
        }
    }
}

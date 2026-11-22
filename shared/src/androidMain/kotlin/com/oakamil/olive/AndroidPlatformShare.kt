// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import android.content.ClipData
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val EXPORT_RETENTION_MS = 60L * 60L * 1000L

@Composable
actual fun rememberFileSharer(): suspend (fileName: String, mimeType: String, content: String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { fileName, mimeType, content ->
            val safeName = File(fileName).name
                .takeUnless { it.isBlank() || it == "." || it == ".." }
                ?: "export.csv"
            var runDir: File? = null
            try {
                val uri = withContext(Dispatchers.IO) {
                    val exportRoot = File(context.cacheDir, "exports").apply { mkdirs() }
                    val cutoff = System.currentTimeMillis() - EXPORT_RETENTION_MS
                    exportRoot.listFiles()?.forEach { child ->
                        if (child.lastModified() < cutoff) {
                            child.deleteRecursively()
                        }
                    }
                    val dir = File(exportRoot, UUID.randomUUID().toString()).apply { mkdirs() }
                    runDir = dir
                    val outFile = File(dir, safeName)
                    outFile.writeText(content, Charsets.UTF_8)
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        outFile
                    )
                }
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, safeName)
                    clipData = ClipData.newRawUri(safeName, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(shareIntent, safeName).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            } catch (e: CancellationException) {
                runDir?.deleteRecursively()
                throw e
            } catch (e: Exception) {
                runDir?.deleteRecursively()
                Log.e("AndroidPlatformShare", "Failed to share $safeName: ${e.message}")
            }
        }
    }
}

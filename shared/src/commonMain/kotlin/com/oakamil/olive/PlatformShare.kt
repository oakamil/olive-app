// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.runtime.Composable

const val MAX_CSV_IMPORT_BYTES = 5 * 1024 * 1024

enum class FilePickError {
    READ_FAILED,
    TOO_LARGE
}

@Composable
expect fun rememberFileSharer(): suspend (fileName: String, mimeType: String, content: String) -> Unit

@Composable
expect fun rememberCsvFilePicker(
    onFilePicked: (String) -> Unit,
    onError: (FilePickError) -> Unit
): () -> Unit

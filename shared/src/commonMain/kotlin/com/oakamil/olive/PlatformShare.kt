// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.runtime.Composable

@Composable
expect fun rememberFileSharer(): (fileName: String, mimeType: String, content: String) -> Unit

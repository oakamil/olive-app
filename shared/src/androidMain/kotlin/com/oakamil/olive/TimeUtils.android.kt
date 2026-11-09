// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun is24HourFormat(): Boolean {
    val context = LocalContext.current
    return android.text.format.DateFormat.is24HourFormat(context)
}

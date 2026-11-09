// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.runtime.Composable
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale

@Composable
actual fun is24HourFormat(): Boolean {
    val format =
        NSDateFormatter.dateFormatFromTemplate("j", options = 0u, locale = NSLocale.currentLocale)
            ?: ""
    return !format.contains("a")
}

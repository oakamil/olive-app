// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import platform.Foundation.NSLog

@Suppress("unused")
actual object Log {
    private fun log(level: String, tag: String, msg: String, tr: Throwable? = null) {
        val extra = tr?.let { "\n${it.stackTraceToString()}" } ?: ""
        NSLog("[$level] [$tag] $msg$extra".replace("%", "%%"))
    }

    actual fun v(tag: String, msg: String) = log("VERBOSE", tag, msg)

    actual fun d(tag: String, msg: String) = log("DEBUG", tag, msg)

    actual fun i(tag: String, msg: String) = log("INFO", tag, msg)

    actual fun w(tag: String, msg: String, tr: Throwable?) = log("WARN", tag, msg, tr)

    actual fun e(tag: String, msg: String, tr: Throwable?) = log("ERROR", tag, msg, tr)
}

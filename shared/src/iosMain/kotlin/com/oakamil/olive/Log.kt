package com.oakamil.olive

import platform.Foundation.NSLog

actual object Log {
    actual fun v(tag: String, msg: String) {
        NSLog("[VERBOSE] [%s] %s", tag, msg)
    }

    actual fun d(tag: String, msg: String) {
        NSLog("[DEBUG] [%s] %s", tag, msg)
    }

    actual fun i(tag: String, msg: String) {
        NSLog("[INFO] [%s] %s", tag, msg)
    }

    actual fun w(tag: String, msg: String, tr: Throwable?) {
        val extra = tr?.let { "\n${it.stackTraceToString()}" } ?: ""
        NSLog("[WARN] [%s] %s%s", tag, msg, extra)
    }

    actual fun e(tag: String, msg: String, tr: Throwable?) {
        val extra = tr?.let { "\n${it.stackTraceToString()}" } ?: ""
        NSLog("[ERROR] [%s] %s%s", tag, msg, extra)
    }
}

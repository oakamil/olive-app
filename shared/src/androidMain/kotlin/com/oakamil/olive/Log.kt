// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

@Suppress("unused", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual object Log {
    actual fun v(tag: String, msg: String) {
        android.util.Log.v(tag, msg)
    }

    actual fun d(tag: String, msg: String) {
        android.util.Log.d(tag, msg)
    }

    actual fun i(tag: String, msg: String) {
        android.util.Log.i(tag, msg)
    }

    actual fun w(tag: String, msg: String, tr: Throwable?) {
        if (tr != null) {
            android.util.Log.w(tag, msg, tr)
        } else {
            android.util.Log.w(tag, msg)
        }
    }

    actual fun e(tag: String, msg: String, tr: Throwable?) {
        if (tr != null) {
            android.util.Log.e(tag, msg, tr)
        } else {
            android.util.Log.e(tag, msg)
        }
    }
}

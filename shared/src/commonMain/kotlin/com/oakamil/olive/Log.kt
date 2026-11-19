package com.oakamil.olive

@Suppress("unused", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object Log {
    fun v(tag: String, msg: String)
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String, tr: Throwable? = null)
    fun e(tag: String, msg: String, tr: Throwable? = null)
}

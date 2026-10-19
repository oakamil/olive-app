// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

interface KeyValueStorage {
    fun getString(key: String, defValue: String? = null): String?
    fun putString(key: String, value: String)
    fun getInt(key: String, defValue: Int): Int
    fun putInt(key: String, value: Int)
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getFloat(key: String, defValue: Float): Float
    fun putFloat(key: String, value: Float)
    fun getStringSet(key: String, defValue: Set<String>? = null): Set<String>?
    fun putStringSet(key: String, value: Set<String>)
    fun contains(key: String): Boolean
    fun remove(key: String)
}

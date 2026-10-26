// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class SharedPreferencesStorage(private val prefs: SharedPreferences) : KeyValueStorage {
    constructor(context: Context) : this(
        context.getSharedPreferences(
            "olive_settings",
            Context.MODE_PRIVATE
        )
    )

    override fun getString(key: String, defValue: String?): String? = prefs.getString(key, defValue)
    override fun putString(key: String, value: String) = prefs.edit { putString(key, value) }
    override fun getInt(key: String, defValue: Int): Int = prefs.getInt(key, defValue)
    override fun putInt(key: String, value: Int) = prefs.edit { putInt(key, value) }
    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        prefs.getBoolean(key, defValue)

    override fun putBoolean(key: String, value: Boolean) = prefs.edit { putBoolean(key, value) }
    override fun getFloat(key: String, defValue: Float): Float = prefs.getFloat(key, defValue)
    override fun putFloat(key: String, value: Float) = prefs.edit { putFloat(key, value) }
    override fun getStringSet(key: String, defValue: Set<String>?): Set<String>? =
        prefs.getStringSet(key, defValue)

    override fun putStringSet(key: String, value: Set<String>) =
        prefs.edit { putStringSet(key, value) }

    override fun contains(key: String): Boolean = prefs.contains(key)
    override fun remove(key: String) = prefs.edit { remove(key) }
}

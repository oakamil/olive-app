package com.oakamil.olive

import android.content.SharedPreferences
import androidx.core.content.edit

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

class SharedPreferencesStorage(private val prefs: SharedPreferences) : KeyValueStorage {
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

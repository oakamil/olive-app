// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import platform.Foundation.NSUserDefaults

class NSUserDefaultsStorage(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults
) : KeyValueStorage {
    override fun getString(key: String, defValue: String?): String? =
        defaults.stringForKey(key) ?: defValue

    override fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    override fun getInt(key: String, defValue: Int): Int =
        if (defaults.objectForKey(key) != null) defaults.integerForKey(key).toInt() else defValue

    override fun putInt(key: String, value: Int) {
        defaults.setInteger(value.toLong(), forKey = key)
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else defValue

    override fun putBoolean(key: String, value: Boolean) {
        defaults.setBool(value, forKey = key)
    }

    override fun getFloat(key: String, defValue: Float): Float =
        if (defaults.objectForKey(key) != null) defaults.floatForKey(key) else defValue

    override fun putFloat(key: String, value: Float) {
        defaults.setFloat(value, forKey = key)
    }

    override fun getStringSet(key: String, defValue: Set<String>?): Set<String>? {
        val array = defaults.stringArrayForKey(key) ?: return defValue
        @Suppress("UNCHECKED_CAST")
        return (array as List<String>).toSet()
    }

    override fun putStringSet(key: String, value: Set<String>) {
        defaults.setObject(value.toList(), forKey = key)
    }

    override fun contains(key: String): Boolean = defaults.objectForKey(key) != null

    override fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }
}

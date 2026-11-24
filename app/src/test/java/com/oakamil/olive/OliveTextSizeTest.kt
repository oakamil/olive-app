// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import org.junit.Assert.assertEquals
import org.junit.Test

class OliveTextSizeTest {

    private class FakeKeyValueStorage : KeyValueStorage {
        private val strings = mutableMapOf<String, String>()
        private val ints = mutableMapOf<String, Int>()
        private val floats = mutableMapOf<String, Float>()
        private val booleans = mutableMapOf<String, Boolean>()
        private val stringSets = mutableMapOf<String, Set<String>>()

        override fun getString(key: String, defValue: String?): String? =
            strings[key] ?: defValue

        override fun putString(key: String, value: String) {
            strings[key] = value
        }

        override fun getInt(key: String, defValue: Int): Int =
            ints[key] ?: defValue

        override fun putInt(key: String, value: Int) {
            ints[key] = value
        }

        override fun getFloat(key: String, defValue: Float): Float =
            floats[key] ?: defValue

        override fun putFloat(key: String, value: Float) {
            floats[key] = value
        }

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            booleans[key] ?: defValue

        override fun putBoolean(key: String, value: Boolean) {
            booleans[key] = value
        }

        override fun getStringSet(key: String, defValue: Set<String>?): Set<String>? =
            stringSets[key] ?: defValue

        override fun putStringSet(key: String, value: Set<String>) {
            stringSets[key] = value
        }

        override fun contains(key: String): Boolean =
            key in strings || key in ints || key in floats || key in booleans || key in stringSets

        override fun remove(key: String) {
            strings.remove(key)
            ints.remove(key)
            floats.remove(key)
            booleans.remove(key)
            stringSets.remove(key)
        }
    }

    @Test
    fun textSizePresetsExposeExpectedKeysAndScales() {
        assertEquals(4, OliveTextSize.entries.size)
        assertEquals("small", OliveTextSize.SMALL.key)
        assertEquals(0.85f, OliveTextSize.SMALL.scale, 1e-6f)
        assertEquals("normal", OliveTextSize.NORMAL.key)
        assertEquals(1.0f, OliveTextSize.NORMAL.scale, 1e-6f)
        assertEquals("large", OliveTextSize.LARGE.key)
        assertEquals(1.15f, OliveTextSize.LARGE.scale, 1e-6f)
        assertEquals("extra_large", OliveTextSize.EXTRA_LARGE.key)
        assertEquals(1.30f, OliveTextSize.EXTRA_LARGE.scale, 1e-6f)
    }

    @Test
    fun fromKeyResolvesPresetsAndFallsBackToNormal() {
        for (preset in OliveTextSize.entries) {
            assertEquals(preset, OliveTextSize.fromKey(preset.key))
        }
        assertEquals(OliveTextSize.NORMAL, OliveTextSize.fromKey(null))
        assertEquals(OliveTextSize.NORMAL, OliveTextSize.fromKey(""))
        assertEquals(OliveTextSize.NORMAL, OliveTextSize.fromKey("extra_small"))
        assertEquals(OliveTextSize.NORMAL, OliveTextSize.fromKey("huge"))
    }

    @Test
    fun settingsManagerPersistsTextSizeWithNormalDefault() {
        val storage = FakeKeyValueStorage()
        val settings = SettingsManager(storage)

        assertEquals(OliveTextSize.NORMAL.key, settings.textSize)

        for (preset in OliveTextSize.entries) {
            settings.textSize = preset.key
            assertEquals(preset.key, settings.textSize)
            assertEquals(preset, OliveTextSize.fromKey(SettingsManager(storage).textSize))
        }
    }
}

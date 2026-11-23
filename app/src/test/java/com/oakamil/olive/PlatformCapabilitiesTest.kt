package com.oakamil.olive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformCapabilitiesTest {

    @Test
    fun testInternalRequestedWithoutInternalOrExternalFallsBackToDemo() {
        val noBackAndroid = AndroidPlatformCapabilities(hasInternalCamera = false)
        assertEquals(
            "demo",
            noBackAndroid.resolveCameraSelection("internal", isExternalConnected = false)
        )

        val noBackIos =
            ApplePlatformCapabilities(supportsUsbCamera = false, hasInternalCamera = false)
        assertEquals(
            "demo",
            noBackIos.resolveCameraSelection("internal", isExternalConnected = true)
        )
    }

    @Test
    fun testInternalRequestedWithoutInternalUsesConnectedExternalWhenSupported() {
        val noBackAndroid = AndroidPlatformCapabilities(hasInternalCamera = false)
        assertEquals(
            "external",
            noBackAndroid.resolveCameraSelection("internal", isExternalConnected = true)
        )

        val noBackIpad =
            ApplePlatformCapabilities(supportsUsbCamera = true, hasInternalCamera = false)
        assertEquals(
            "external",
            noBackIpad.resolveCameraSelection("internal", isExternalConnected = true)
        )
    }

    @Test
    fun testExternalDisconnectedFallbackDependsOnInternalCamera() {
        val withBack = AndroidPlatformCapabilities(hasInternalCamera = true)
        assertEquals(
            "internal",
            withBack.resolveCameraSelection("external", isExternalConnected = false)
        )

        val withoutBack = AndroidPlatformCapabilities(hasInternalCamera = false)
        assertEquals(
            "demo",
            withoutBack.resolveCameraSelection("external", isExternalConnected = false)
        )
    }

    @Test
    fun testExplicitValidSelectionsPreserved() {
        val withBack = AndroidPlatformCapabilities(hasInternalCamera = true)
        assertEquals(
            "internal",
            withBack.resolveCameraSelection("internal", isExternalConnected = true)
        )
        assertEquals(
            "external",
            withBack.resolveCameraSelection("external", isExternalConnected = true)
        )
        assertEquals("demo", withBack.resolveCameraSelection("demo", isExternalConnected = true))
    }

    @Test
    fun testAndroidWithoutUsbHostNeverSelectsExternal() {
        val noUsbWithBack =
            AndroidPlatformCapabilities(supportsUsbCamera = false, hasInternalCamera = true)
        assertEquals(
            "internal",
            noUsbWithBack.resolveCameraSelection("external", isExternalConnected = true)
        )

        val noUsbNoBack =
            AndroidPlatformCapabilities(supportsUsbCamera = false, hasInternalCamera = false)
        assertEquals(
            "demo",
            noUsbNoBack.resolveCameraSelection("external", isExternalConnected = true)
        )
        assertEquals(
            "demo",
            noUsbNoBack.resolveCameraSelection("internal", isExternalConnected = true)
        )
    }

    @Test
    fun testSupportsLocationDefaultsAndOverride() {
        assertTrue(AndroidPlatformCapabilities.supportsLocation)
        assertTrue(ApplePlatformCapabilities.supportsLocation)
        assertFalse(AndroidPlatformCapabilities(supportsLocation = false).supportsLocation)
    }
}

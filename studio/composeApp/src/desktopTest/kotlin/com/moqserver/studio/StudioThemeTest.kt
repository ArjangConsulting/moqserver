package com.moqserver.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioThemeTest {

    @Test
    fun `resolveDarkTheme uses system theme in system mode`() {
        assertTrue(resolveDarkTheme(StudioThemeMode.SYSTEM, systemInDarkTheme = true))
        assertFalse(resolveDarkTheme(StudioThemeMode.SYSTEM, systemInDarkTheme = false))
    }

    @Test
    fun `resolveDarkTheme honors explicit override modes`() {
        assertFalse(resolveDarkTheme(StudioThemeMode.LIGHT, systemInDarkTheme = true))
        assertTrue(resolveDarkTheme(StudioThemeMode.DARK, systemInDarkTheme = false))
    }

    @Test
    fun `resolveNextThemeMode flips effective dark theme for system mode`() {
        assertEquals(
            StudioThemeMode.LIGHT,
            resolveNextThemeMode(StudioThemeMode.SYSTEM, systemInDarkTheme = true),
        )
        assertEquals(
            StudioThemeMode.DARK,
            resolveNextThemeMode(StudioThemeMode.SYSTEM, systemInDarkTheme = false),
        )
    }

    @Test
    fun `resolveNextThemeMode flips explicit theme modes`() {
        assertEquals(
            StudioThemeMode.DARK,
            resolveNextThemeMode(StudioThemeMode.LIGHT, systemInDarkTheme = false),
        )
        assertEquals(
            StudioThemeMode.LIGHT,
            resolveNextThemeMode(StudioThemeMode.DARK, systemInDarkTheme = true),
        )
    }
}

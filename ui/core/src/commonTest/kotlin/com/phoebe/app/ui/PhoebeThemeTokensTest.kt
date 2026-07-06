package com.phoebe.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PhoebeThemeTokensTest {
    @Test
    fun designSystemFallsBackToDefault() {
        assertEquals(PhoebeDesignSystem.Default, PhoebeDesignSystem.fromId(null))
        assertEquals(PhoebeDesignSystem.Default, PhoebeDesignSystem.fromId("missing"))
        assertEquals(PhoebeDesignSystem.Porcelain, PhoebeDesignSystem.fromId("Porcelain"))
    }

    @Test
    fun eachDesignHasLightDarkPalettesAndCuratedAccents() {
        PhoebeDesignSystem.Options.forEach { design ->
            val pack = design.themePack()

            assertEquals(design, pack.design)
            assertTrue(pack.accentOptions.isNotEmpty())
            assertFalse(pack.lightPalette == pack.darkPalette)
        }
    }

    @Test
    fun invalidCuratedTintFallsBackToDesignDefault() {
        val nocturne = PhoebeDesignSystem.Nocturne.themePack()

        assertSame(PhoebeTintOption.NocturneBrass, nocturne.resolveTint("purple"))
        assertSame(PhoebeTintOption.NocturneBrass, PhoebeTintOption.fromId("red", PhoebeDesignSystem.Nocturne))
    }

    @Test
    fun defaultDesignKeepsCurrentTintPalette() {
        val default = PhoebeDesignSystem.Default.themePack()

        assertTrue(PhoebeTintOption.Purple in default.accentOptions)
        assertSame(PhoebeTintOption.fromId("red"), PhoebeTintOption.fromId("red", PhoebeDesignSystem.Default))
        assertEquals(PhoebeTintOption.Options, default.accentOptions)
    }
}

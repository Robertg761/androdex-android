package io.androdex.android.ui.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemodexInputFieldPresentationTest {
    @Test
    fun defaultVariantKeepsLegacyInputChrome() {
        val spec = remodexInputChromeSpec(RemodexInputFieldVariant.Default)

        assertEquals(16, spec.cornerRadiusDp)
        assertEquals(14, spec.horizontalPaddingDp)
        assertEquals(12, spec.verticalPaddingDp)
        assertFalse(spec.useAnimatedFocusChrome)
        assertFalse(spec.useBodyMediumText)
    }

    @Test
    fun threadVariantOptsIntoPolishedThreadChrome() {
        val spec = remodexInputChromeSpec(RemodexInputFieldVariant.Thread)

        assertEquals(18, spec.cornerRadiusDp)
        assertEquals(44, spec.minHeightDp)
        assertTrue(spec.useAnimatedFocusChrome)
        assertTrue(spec.useBodyMediumText)
    }
}

package com.eza.hyperglow.root.lockscreen

import com.eza.hyperglow.customization.CARD_MATERIAL_SOFT
import org.junit.Assert.assertEquals
import org.junit.Test

class CardBlurRadiusTest {
    @Test
    fun zeroStrengthIsZero() {
        assertEquals(0f, cardBlurRadiusDp(CARD_MATERIAL_SOFT, 0)!!, 0.001f)
    }

    @Test
    fun fiftyPercentIsThreeQuarterBase() {
        assertEquals(15f, cardBlurRadiusDp(CARD_MATERIAL_SOFT, 50)!!, 0.001f)
    }

    @Test
    fun hundredPercentIsOneAndHalfBase() {
        assertEquals(30f, cardBlurRadiusDp(CARD_MATERIAL_SOFT, 100)!!, 0.001f)
    }
}

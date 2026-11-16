package com.fragmind.camnode.feature.applock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalculatorEngineTest {
    private fun type(keys: String): CalculatorState = keys.fold(CalculatorState()) { state, key -> CalculatorEngine.press(state, key) }

    @Test
    fun itCalculatesLikeANormalCalculator() {
        assertEquals("21", type("12+9=").display)
        assertEquals("7.5", type("15÷2=").display)
        assertEquals("20", type("2+3×4=").display)
        assertEquals("Error", type("5÷0=").display)
        assertEquals("12", type("123<").display)
    }

    @Test
    fun typingFourDigitsAndEqualsIsAPinCandidate() {
        assertEquals("2580", CalculatorEngine.pinCandidate(type("2580")))
        assertNull(CalculatorEngine.pinCandidate(type("258")))
        assertNull(CalculatorEngine.pinCandidate(type("12+2568")))
        assertNull("a result is not typed input", CalculatorEngine.pinCandidate(type("1290+1290=")))
    }
}

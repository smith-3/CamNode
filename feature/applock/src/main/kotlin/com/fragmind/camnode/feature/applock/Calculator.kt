package com.fragmind.camnode.feature.applock

import com.fragmind.camnode.core.model.AppPin
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** A plain calculator: one pending operation, evaluated left to right like a pocket calculator. */
data class CalculatorState(
    val display: String = "0",
    val accumulator: BigDecimal? = null,
    val operator: Char? = null,
    /** The next digit starts a new number instead of extending the display. */
    val startNew: Boolean = true,
)

object CalculatorEngine {
    const val DIVIDE = '÷'
    const val MULTIPLY = '×'
    const val MINUS = '−'
    const val PLUS = '+'
    private const val ERROR = "Error"
    private const val MAX_DIGITS = 12

    fun press(state: CalculatorState, key: Char): CalculatorState = when (key) {
        in '0'..'9' -> digit(state, key)
        '.' -> decimal(state)
        DIVIDE, MULTIPLY, MINUS, PLUS -> operator(state, key)
        '=' -> equals(state)
        'C' -> CalculatorState()
        '<' -> backspace(state)
        '%' -> state.copy(display = format(state.value().divide(BigDecimal(100))), startNew = true)
        else -> state
    }

    /**
     * The disguise unlocks when someone types exactly four digits and presses "=" with no
     * operation pending, which is what typing the PIN looks like.
     */
    fun pinCandidate(state: CalculatorState): String? =
        state.display.takeIf { state.operator == null && state.accumulator == null && !state.startNew && AppPin.isWellFormed(it) }

    private fun digit(state: CalculatorState, key: Char): CalculatorState {
        if (state.startNew || state.display == "0" || state.display == ERROR) return state.copy(display = key.toString(), startNew = false)
        if (state.display.count(Char::isDigit) >= MAX_DIGITS) return state
        return state.copy(display = state.display + key)
    }

    private fun decimal(state: CalculatorState): CalculatorState = when {
        state.startNew || state.display == ERROR -> state.copy(display = "0.", startNew = false)
        '.' in state.display -> state
        else -> state.copy(display = state.display + ".")
    }

    private fun operator(state: CalculatorState, key: Char): CalculatorState {
        val chained = if (state.operator != null && !state.startNew) equals(state) else state
        return chained.copy(accumulator = chained.value(), operator = key, startNew = true)
    }

    private fun equals(state: CalculatorState): CalculatorState {
        val left = state.accumulator ?: return state.copy(startNew = true)
        val operator = state.operator ?: return state
        val right = state.value()
        val result = when (operator) {
            PLUS -> left.add(right)
            MINUS -> left.subtract(right)
            MULTIPLY -> left.multiply(right)
            else -> if (right.signum() == 0) null else left.divide(right, MathContext.DECIMAL64)
        }
        return CalculatorState(display = result?.let(::format) ?: ERROR, startNew = true)
    }

    private fun backspace(state: CalculatorState): CalculatorState {
        if (state.startNew || state.display == ERROR) return state
        val shorter = state.display.dropLast(1)
        return state.copy(display = if (shorter.isEmpty() || shorter == "-") "0" else shorter)
    }

    private fun CalculatorState.value(): BigDecimal = display.toBigDecimalOrNull() ?: BigDecimal.ZERO

    private fun format(value: BigDecimal): String {
        val rounded = value.round(MathContext(MAX_DIGITS, RoundingMode.HALF_UP)).stripTrailingZeros()
        return if (rounded.scale() <= 0) rounded.toBigInteger().toString() else rounded.toPlainString()
    }
}

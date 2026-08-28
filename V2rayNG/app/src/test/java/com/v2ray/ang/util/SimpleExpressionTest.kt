package com.v2ray.ang.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decoy calculator is only convincing if it actually calculates, so the parser gets the same
 * scrutiny as the rest of the app.
 */
class SimpleExpressionTest {

    @Test
    fun test_evaluate_theDefaultUnlockExpression() {
        assertEquals(3936256.0, SimpleExpression.evaluate("1984*1984")!!, 0.0001)
    }

    @Test
    fun test_evaluate_respectsPrecedence() {
        assertEquals(14.0, SimpleExpression.evaluate("2+3*4")!!, 0.0001)
        assertEquals(2.0, SimpleExpression.evaluate("10-4-4")!!, 0.0001)
        assertEquals(5.0, SimpleExpression.evaluate("10/4*2")!!, 0.0001)
    }

    @Test
    fun test_evaluate_handlesKeypadGlyphsAndDecimals() {
        assertEquals(6.0, SimpleExpression.evaluate("2×3")!!, 0.0001)
        assertEquals(2.0, SimpleExpression.evaluate("6÷3")!!, 0.0001)
        assertEquals(1.0, SimpleExpression.evaluate("3−2")!!, 0.0001)
        assertEquals(0.75, SimpleExpression.evaluate("0.5+0.25")!!, 0.0001)
    }

    @Test
    fun test_evaluate_handlesLeadingMinus() {
        assertEquals(-5.0, SimpleExpression.evaluate("-5")!!, 0.0001)
        assertEquals(1.0, SimpleExpression.evaluate("-2+3")!!, 0.0001)
    }

    @Test
    fun test_evaluate_returnsNullForMalformedInput() {
        assertNull(SimpleExpression.evaluate(""))
        assertNull(SimpleExpression.evaluate("2+"))
        assertNull(SimpleExpression.evaluate("+"))
        assertNull(SimpleExpression.evaluate("abc"))
    }

    @Test
    fun test_evaluate_returnsNullOnDivisionByZero() {
        assertNull(SimpleExpression.evaluate("5/0"))
    }

    @Test
    fun test_isKeypadTypable_rejectsWhatTheKeypadCannotProduce() {
        assertTrue(SimpleExpression.isKeypadTypable("1984*1984"))
        assertTrue(SimpleExpression.isKeypadTypable("12×3"))
        assertTrue(SimpleExpression.isKeypadTypable("2^8"))
        assertTrue(SimpleExpression.isKeypadTypable("√16"))
        assertTrue(SimpleExpression.isKeypadTypable("(2+3)"))
        assertFalse(SimpleExpression.isKeypadTypable("hunter2"))
        assertFalse(SimpleExpression.isKeypadTypable("2+3="))
        assertFalse(SimpleExpression.isKeypadTypable(""))
    }

    @Test
    fun test_evaluate_power() {
        assertEquals(256.0, SimpleExpression.evaluate("2^8")!!, 0.0001)
        // Right-associative: 2^(3^2), not (2^3)^2.
        assertEquals(512.0, SimpleExpression.evaluate("2^3^2")!!, 0.0001)
        // Binds tighter than multiplication.
        assertEquals(18.0, SimpleExpression.evaluate("2*3^2")!!, 0.0001)
    }

    @Test
    fun test_evaluate_root() {
        assertEquals(4.0, SimpleExpression.evaluate("√16")!!, 0.0001)
        assertEquals(7.0, SimpleExpression.evaluate("√16+3")!!, 0.0001)
        assertNull(SimpleExpression.evaluate("√-4"))
    }

    @Test
    fun test_evaluate_brackets() {
        assertEquals(20.0, SimpleExpression.evaluate("(2+3)*4")!!, 0.0001)
        assertEquals(14.0, SimpleExpression.evaluate("2+(3*4)")!!, 0.0001)
        assertEquals(3.0, SimpleExpression.evaluate("√(4+5)")!!, 0.0001)
        assertEquals(-1.0, SimpleExpression.evaluate("((1-2))")!!, 0.0001)
    }

    @Test
    fun test_evaluate_rejectsUnbalancedBrackets() {
        assertNull(SimpleExpression.evaluate("(2+3"))
        assertNull(SimpleExpression.evaluate("2+3)"))
        assertNull(SimpleExpression.evaluate("()"))
    }

    @Test
    fun test_withGroupSeparators_groupsIntegersOnly() {
        assertEquals("3 936 256", SimpleExpression.withGroupSeparators("3936256"))
        assertEquals("999", SimpleExpression.withGroupSeparators("999"))
        assertEquals("1 000", SimpleExpression.withGroupSeparators("1000"))
        // The fractional part is never grouped.
        assertEquals("1 234.56789", SimpleExpression.withGroupSeparators("1234.56789"))
        // Operators and brackets pass through untouched.
        assertEquals("1 984×1 984", SimpleExpression.withGroupSeparators("1984×1984"))
        assertEquals("(1 500+20)", SimpleExpression.withGroupSeparators("(1500+20)"))
    }

    @Test
    fun test_normalize_dropsGroupSeparatorsSoDisplayValuesReparse() {
        assertEquals(3936256.0, SimpleExpression.evaluate("3 936 256")!!, 0.0001)
    }

    @Test
    fun test_evaluate_percent() {
        assertEquals(0.5, SimpleExpression.evaluate("50%")!!, 0.0001)
        assertEquals(25.0, SimpleExpression.evaluate("50%*50")!!, 0.0001)
    }
}

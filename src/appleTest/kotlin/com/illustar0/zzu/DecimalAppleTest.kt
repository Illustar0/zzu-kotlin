package com.illustar0.zzu

import kotlin.test.Test
import kotlin.test.assertEquals
import platform.Foundation.NSComparisonResult
import platform.Foundation.NSDecimalNumber

class DecimalAppleTest {
    @Test
    fun convertsToNSDecimalNumberWithExactValue() {
        val samples = listOf("0", "0.00", "-12.30", "90071992547409.9300")
        samples.forEach { literal ->
            val decimal = Decimal.parse(literal)
            val converted = decimal.toNSDecimalNumber()
            val expected = NSDecimalNumber.decimalNumberWithString(literal)
            val order: NSComparisonResult = converted.compare(expected)
            assertEquals(0L, order)
        }
    }
}

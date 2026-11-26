package com.illustar0.zzu

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class DecimalJvmTest {
    @Test
    fun roundTripsWithBigDecimalPreservingValueAndScale() {
        val samples = listOf("0", "0.00", "-12.30", "90071992547409.9300")
        samples.forEach { text ->
            val decimal = Decimal.parse(text)
            val bigDecimal = decimal.toBigDecimal()
            assertEquals(BigDecimal(text), bigDecimal)
            assertEquals(decimal.scale, bigDecimal.scale())

            val roundTripped = bigDecimal.toDecimal()
            assertEquals(decimal, roundTripped)
            assertEquals(decimal.scale, roundTripped.scale)
            assertEquals(text, roundTripped.toPlainString())
        }

        val scientific = BigDecimal("1.23E+4").toDecimal()
        assertEquals(0, scientific.scale)
        assertEquals("12300", scientific.toPlainString())
    }
}

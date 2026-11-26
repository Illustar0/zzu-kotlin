package com.illustar0.zzu

import com.illustar0.zzu.ecard.EcardClient
import com.illustar0.zzu.internal.ZzuJson
import com.illustar0.zzu.internal.ZzuRpc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class DecimalTest {
    @Test
    fun parsesPlainAndScientificLiteralsWhilePreservingScale() {
        val plain = Decimal.parse("+0012.30")
        assertEquals(2, plain.scale)
        assertEquals("12.30", plain.toPlainString())
        assertEquals("12.30", plain.toString())

        val fraction = Decimal.parse("0.0120")
        assertEquals(4, fraction.scale)
        assertEquals("0.0120", fraction.toPlainString())

        val negativeExponent = Decimal.parse("1.20e-2")
        assertEquals(4, negativeExponent.scale)
        assertEquals("0.0120", negativeExponent.toPlainString())

        val positiveExponent = Decimal.parse("-1.23E+4")
        assertEquals(0, positiveExponent.scale)
        assertEquals("-12300", positiveExponent.toPlainString())

        val leadingDot = Decimal.parse(".50")
        assertEquals(2, leadingDot.scale)
        assertEquals("0.50", leadingDot.toPlainString())
    }

    @Test
    fun normalizesNegativeZeroWhileKeepingScale() {
        val negativeZero = Decimal.parse("-0.00")
        val positiveZero = Decimal.parse("0.00")

        assertEquals(2, negativeZero.scale)
        assertEquals("0.00", negativeZero.toPlainString())
        assertEquals("0.00", (-positiveZero).toPlainString())
        assertEquals(Decimal.ZERO, negativeZero)
        assertEquals(Decimal.ZERO.hashCode(), negativeZero.hashCode())
        assertEquals(0, negativeZero.compareTo(Decimal.ZERO))
    }

    @Test
    fun equalsHashCodeAndCompareToUseNormalizedNumericValue() {
        val twoDecimals = Decimal.parse("12.30")
        val oneDecimal = Decimal.parse("12.3")
        val integerWithZeros = Decimal.parse("120.00")
        val plainInteger = Decimal(120)

        assertEquals(twoDecimals, oneDecimal)
        assertEquals(twoDecimals.hashCode(), oneDecimal.hashCode())
        assertEquals(0, twoDecimals.compareTo(oneDecimal))

        assertEquals(integerWithZeros, plainInteger)
        assertEquals(integerWithZeros.hashCode(), plainInteger.hashCode())
        assertFalse(plainInteger.equals(120))
        assertNotEquals(Decimal.parse("120"), Decimal.parse("12"))

        val ordered = listOf(
            Decimal.parse("-100.0"),
            Decimal.parse("-12.34"),
            Decimal.parse("-12.3"),
            Decimal.parse("-0.10"),
            Decimal.parse("-0.099"),
            Decimal.parse("0.00"),
            Decimal.parse("0.099"),
            Decimal.parse("0.1"),
            Decimal.parse("12.30"),
            Decimal.parse("12.34"),
            Decimal.parse("100"),
        )
        assertEquals(ordered, ordered.reversed().sorted())
    }

    @Test
    fun supportsExactAdditionSubtractionAndIntLongConstructors() {
        assertEquals("-2147483648", Decimal(Int.MIN_VALUE).toPlainString())
        assertEquals("-9223372036854775808", Decimal(Long.MIN_VALUE).toPlainString())
        assertEquals("9223372036854775807", Decimal(Long.MAX_VALUE).toPlainString())

        val sum = Decimal.parse("12.30") + Decimal.parse("0.7")
        assertEquals(2, sum.scale)
        assertEquals("13.00", sum.toPlainString())

        val carrySum = Decimal.parse("99.95") + Decimal.parse("0.050")
        assertEquals(3, carrySum.scale)
        assertEquals("100.000", carrySum.toPlainString())

        val diffAcrossZero = Decimal.parse("5.2") - Decimal.parse("12.35")
        assertEquals(2, diffAcrossZero.scale)
        assertEquals("-7.15", diffAcrossZero.toPlainString())

        val cancelToZero = Decimal.parse("-12.30") + Decimal.parse("12.3")
        assertEquals(2, cancelToZero.scale)
        assertEquals("0.00", cancelToZero.toPlainString())
    }

    @Test
    fun rejectsMalformedLiteralsAndMapsCorruptBalanceToDecodeException() = runTest {
        val invalidInputs = listOf(
            "",
            " ",
            " 12.3",
            "12.3 ",
            ".",
            "+.",
            "-.",
            "1.2.3",
            "NaN",
            "Infinity",
            "-Infinity",
            "1e",
            "1e+",
            "e5",
            "12_34",
            "12a",
            "1e100000",
        )
        invalidInputs.forEach { literal ->
            assertFailsWith<NumberFormatException>("应拒绝 $literal") {
                Decimal.parse(literal)
            }
        }

        val encoded = ZzuJson.encodeToString(Decimal.parse("90071992547409.9300"))
        assertEquals("\"90071992547409.9300\"", encoded)
        assertEquals("90071992547409.9300", ZzuJson.decodeFromString<Decimal>(encoded).toPlainString())

        val rpc = ZzuRpc(FakeTransport { "\"not-a-decimal\"" }, StandardTestDispatcher(testScheduler))
        val failure = assertFailsWith<ZzuException.Decode> {
            EcardClient(rpc).balance()
        }
        assertTrue("not-a-decimal" !in failure.toString(), failure.toString())
    }
}

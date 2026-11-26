package com.illustar0.zzu

import com.illustar0.zzu.internal.DecimalSerializer
import kotlinx.serialization.Serializable

private const val BASE_TEN: Int = 10
private const val HASH_MULTIPLIER: Int = 31
private const val MAX_EXPONENT_DIGITS: Int = 6
private const val MAX_SCALE: Int = 10_000

/**
 * 不可变的精确十进制数，用于跨平台无损表示校园卡余额等十进制金额。
 *
 * 数值判等（[equals]）、哈希（[hashCode]）与大小比较（[compareTo]）均基于剥离末尾冗余小数零后的真实数值，
 * 例如 `Decimal.parse("12.30") == Decimal.parse("12.3")` 为 `true` 且两者哈希值相同；
 * 原始小数位数保留在 [scale] 中，并由 [toPlainString] 与 [toString] 原样输出。
 * 负零（如 `"-0.00"`）在构造时统一归一为非负零（`"0.00"`）。
 *
 * @property scale 小数点后的位数，不小于 `0`。
 */
@Serializable(with = DecimalSerializer::class)
public class Decimal private constructor(
    private val isNegative: Boolean,
    private val unscaledDigits: String,
    public val scale: Int,
) : Comparable<Decimal> {
    private val normDigits: String
    private val normScale: Int

    init {
        if (unscaledDigits == "0") {
            normDigits = "0"
            normScale = 0
        } else {
            val stripped = countTrailingZeros(unscaledDigits, scale)
            normDigits = if (stripped == 0) {
                unscaledDigits
            } else {
                unscaledDigits.substring(0, unscaledDigits.length - stripped)
            }
            normScale = scale - stripped
        }
    }

    /** 使用 32 位有符号整数构造小数位为 `0` 的 [Decimal]。 */
    public constructor(value: Int) : this(value.toLong())

    /** 使用 64 位有符号整数构造小数位为 `0` 的 [Decimal]。 */
    public constructor(value: Long) : this(
        isNegative = value < 0L,
        unscaledDigits = if (value < 0L) (0uL - value.toULong()).toString() else value.toString(),
        scale = 0,
    )

    /** 返回符号取反后的 [Decimal]；数值为零时保持非负且保留原有 [scale]。 */
    public operator fun unaryMinus(): Decimal =
        if (unscaledDigits == "0") this else Decimal(!isNegative, unscaledDigits, scale)

    /** 返回与 [other] 相加后的精确结果，结果的 [scale] 取两操作数较大者。 */
    public operator fun plus(other: Decimal): Decimal {
        val targetScale = maxOf(scale, other.scale)
        val left = scaleUpDigits(unscaledDigits, targetScale - scale)
        val right = scaleUpDigits(other.unscaledDigits, targetScale - other.scale)
        return if (isNegative == other.isNegative) {
            val sum = addMagnitudes(left, right)
            Decimal(isNegative && sum != "0", sum, targetScale)
        } else {
            val order = compareMagnitudes(left, right)
            when {
                order == 0 -> Decimal(isNegative = false, unscaledDigits = "0", scale = targetScale)
                order > 0 -> Decimal(isNegative, subtractMagnitudes(left, right), targetScale)
                else -> Decimal(other.isNegative, subtractMagnitudes(right, left), targetScale)
            }
        }
    }

    /** 返回减去 [other] 后的精确结果，结果的 [scale] 取两操作数较大者。 */
    public operator fun minus(other: Decimal): Decimal = plus(-other)

    /** 按十进制真实数值比较大小，忽略末尾冗余小数零差异。 */
    override fun compareTo(other: Decimal): Int = when {
        normDigits == "0" && other.normDigits == "0" -> 0
        isNegative != other.isNegative -> if (isNegative) -1 else 1
        else -> {
            val magnitudeOrder = compareNormalizedMagnitudes(other)
            if (isNegative) -magnitudeOrder else magnitudeOrder
        }
    }

    override fun equals(other: Any?): Boolean =
        other is Decimal &&
            isNegative == other.isNegative &&
            normScale == other.normScale &&
            normDigits == other.normDigits

    override fun hashCode(): Int {
        var result = isNegative.hashCode()
        result = HASH_MULTIPLIER * result + normDigits.hashCode()
        result = HASH_MULTIPLIER * result + normScale
        return result
    }

    /** 返回保留 [scale] 位小数、不含指数部分的十进制字符串表示。 */
    public fun toPlainString(): String = buildString {
        if (isNegative) append('-')
        when {
            scale == 0 -> append(unscaledDigits)
            unscaledDigits == "0" -> {
                append("0.")
                repeat(scale) { append('0') }
            }
            unscaledDigits.length > scale -> {
                val splitIndex = unscaledDigits.length - scale
                append(unscaledDigits, 0, splitIndex)
                append('.')
                append(unscaledDigits, splitIndex, unscaledDigits.length)
            }
            else -> {
                append("0.")
                repeat(scale - unscaledDigits.length) { append('0') }
                append(unscaledDigits)
            }
        }
    }

    /** 等同于 [toPlainString]。 */
    override fun toString(): String = toPlainString()

    private fun compareNormalizedMagnitudes(other: Decimal): Int = when {
        normDigits == "0" -> -1
        other.normDigits == "0" -> 1
        else -> {
            val leftExponent = normDigits.length - normScale
            val rightExponent = other.normDigits.length - other.normScale
            if (leftExponent != rightExponent) {
                leftExponent.compareTo(rightExponent)
            } else {
                normDigits.compareTo(other.normDigits)
            }
        }
    }

    public companion object {
        /** 数值 `0`（[scale] 为 `0`）。 */
        public val ZERO: Decimal = Decimal(0L)

        /**
         * 解析十进制字符串 [value]，支持可选正负号与科学计数法。
         *
         * @throws NumberFormatException [value] 为空、包含空白或非法字符，或超出支持的小数位范围时。
         */
        public fun parse(value: String): Decimal {
            val expIndex = value.indexOfAny(charArrayOf('e', 'E'))
            val mantissaText = if (expIndex < 0) value else value.substring(0, expIndex)
    val exponent = if (expIndex < 0) 0L else parseExponent(value.substring(expIndex + 1))

            val firstChar = mantissaText.firstOrNull()
            val digitsPart = if (firstChar == '+' || firstChar == '-') {
                mantissaText.substring(1)
            } else {
                mantissaText
            }
            val (intPart, fracPart) = splitMantissa(digitsPart)
            val rawDigits = (intPart + fracPart).trimStart('0')
            val adjustedScale = fracPart.length.toLong() - exponent
            if (adjustedScale !in -MAX_SCALE..MAX_SCALE) {
                throw NumberFormatException("十进制数小数位超出支持范围")
            }

            val scale = if (adjustedScale >= 0L) adjustedScale.toInt() else 0
            val unscaledDigits = when {
                rawDigits.isEmpty() -> "0"
                adjustedScale >= 0L -> rawDigits
                else -> rawDigits + "0".repeat((-adjustedScale).toInt())
            }
            return Decimal(firstChar == '-' && unscaledDigits != "0", unscaledDigits, scale)
        }
    }
}

private fun parseExponent(exponentPart: String): Long {
    if (exponentPart.isEmpty()) throw invalidDecimal()
    val firstChar = exponentPart[0]
    val digits = if (firstChar == '+' || firstChar == '-') {
        exponentPart.substring(1)
    } else {
        exponentPart
    }
    if (digits.isEmpty() || digits.length > MAX_EXPONENT_DIGITS || !isAsciiDigits(digits)) {
        throw invalidDecimal()
    }
    val magnitude = digits.toLong()
    return if (firstChar == '-') -magnitude else magnitude
}

private fun splitMantissa(digitsPart: String): Pair<String, String> {
    val dotIndex = digitsPart.indexOf('.')
    val intPart = if (dotIndex < 0) digitsPart else digitsPart.substring(0, dotIndex)
    val fracPart = if (dotIndex < 0) "" else digitsPart.substring(dotIndex + 1)
    val hasValidDigits = (intPart.isNotEmpty() || fracPart.isNotEmpty()) &&
        isAsciiDigits(intPart) &&
        isAsciiDigits(fracPart)
    if (!hasValidDigits) throw invalidDecimal()
    return intPart to fracPart
}

private fun isAsciiDigits(text: String): Boolean = text.all { it in '0'..'9' }

private fun countTrailingZeros(digits: String, maxCount: Int): Int {
    val limit = minOf(maxCount, digits.length)
    var count = 0
    while (count < limit && digits[digits.length - 1 - count] == '0') {
        count++
    }
    return count
}

private fun scaleUpDigits(digits: String, extraZeros: Int): String =
    if (digits == "0" || extraZeros == 0) digits else digits + "0".repeat(extraZeros)

private fun compareMagnitudes(left: String, right: String): Int =
    if (left.length != right.length) left.length.compareTo(right.length) else left.compareTo(right)

private fun addMagnitudes(left: String, right: String): String = when {
    left == "0" -> right
    right == "0" -> left
    else -> {
        val result = CharArray(maxOf(left.length, right.length) + 1)
        var i = left.lastIndex
        var j = right.lastIndex
        var k = result.lastIndex
        var carry = 0
        while (i >= 0 || j >= 0 || carry > 0) {
            val leftDigit = if (i >= 0) left[i--] - '0' else 0
            val rightDigit = if (j >= 0) right[j--] - '0' else 0
            val sum = leftDigit + rightDigit + carry
            result[k--] = ('0'.code + sum % BASE_TEN).toChar()
            carry = sum / BASE_TEN
        }
        result.concatToString(startIndex = k + 1)
    }
}

private fun subtractMagnitudes(larger: String, smaller: String): String {
    val result = CharArray(larger.length)
    var i = larger.lastIndex
    var j = smaller.lastIndex
    var borrow = 0
    while (i >= 0) {
        val topDigit = larger[i] - '0' - borrow
        val bottomDigit = if (j >= 0) smaller[j--] - '0' else 0
        val diff = if (topDigit < bottomDigit) {
            borrow = 1
            topDigit + BASE_TEN - bottomDigit
        } else {
            borrow = 0
            topDigit - bottomDigit
        }
        result[i--] = ('0'.code + diff).toChar()
    }
    return result.concatToString().trimStart('0').ifEmpty { "0" }
}

private fun invalidDecimal(): NumberFormatException =
    NumberFormatException("非法的十进制数格式")

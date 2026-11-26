package com.illustar0.zzu

import java.math.BigDecimal

/** 将 [Decimal] 转换为具有相同数值与 [Decimal.scale] 的 [BigDecimal]。 */
public fun Decimal.toBigDecimal(): BigDecimal = BigDecimal(toPlainString())

/** 将 [BigDecimal] 转换为 [Decimal]；负小数位（科学计数法整数）会展开为普通整数形式。 */
public fun BigDecimal.toDecimal(): Decimal = Decimal.parse(toPlainString())

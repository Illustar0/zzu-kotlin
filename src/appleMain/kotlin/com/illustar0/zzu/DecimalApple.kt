package com.illustar0.zzu

import platform.Foundation.NSDecimalNumber

/** 将 [Decimal] 转换为数值相等的 [NSDecimalNumber]。 */
public fun Decimal.toNSDecimalNumber(): NSDecimalNumber =
    NSDecimalNumber.decimalNumberWithString(toPlainString())

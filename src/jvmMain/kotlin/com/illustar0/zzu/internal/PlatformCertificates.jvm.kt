package com.illustar0.zzu.internal

/** 桌面 JVM 交由 `libzzu` 内置的系统根证书校验器验证服务端证书。 */
internal actual fun platformRootCertificatesPem(): String? = null

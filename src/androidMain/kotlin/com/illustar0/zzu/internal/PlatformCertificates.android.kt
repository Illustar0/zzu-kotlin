package com.illustar0.zzu.internal

import java.security.KeyStore
import java.security.KeyStoreException
import kotlin.io.encoding.Base64

/**
 * 读取系统内置根证书（`system:` 前缀）并编码为 PEM。
 *
 * 不读取用户安装的证书，与 Android 对 `targetSdk` 24 及以上应用的默认信任范围保持一致。
 */
internal actual fun platformRootCertificatesPem(): String? {
    val store = try {
        KeyStore.getInstance("AndroidCAStore").apply { load(null) }
    } catch (_: KeyStoreException) {
        return null
    }
    return store.aliases().asSequence()
        .filter { it.startsWith("system:") }
        .joinToString("\n") { alias ->
            val body = Base64.Mime.encode(store.getCertificate(alias).encoded)
            "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----"
        }
}

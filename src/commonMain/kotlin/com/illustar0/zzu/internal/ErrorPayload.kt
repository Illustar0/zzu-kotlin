package com.illustar0.zzu.internal

import com.illustar0.zzu.AuthenticationErrorKind
import com.illustar0.zzu.RequestContext
import com.illustar0.zzu.TransportErrorKind
import com.illustar0.zzu.ZzuException
import kotlin.enums.enumEntries
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/** 本 SDK 编译时依赖的 C ABI 版本；加载时要求主版本相等、次版本不低于此值。 */
internal const val ABI_VERSION_MAJOR: Int = 2
internal const val ABI_VERSION_MINOR: Int = 1
private const val ABI_VERSION_COMPONENT_BITS: Int = 16
private const val ABI_VERSION_MINOR_MASK: Int = 0xFFFF

internal const val ZZU_OK: Int = 0
internal const val ZZU_INVALID_INPUT: Int = 1
internal const val ZZU_TRANSPORT_ERROR: Int = 2
internal const val ZZU_HTTP_ERROR: Int = 3
internal const val ZZU_DECODE_ERROR: Int = 4
internal const val ZZU_AUTHENTICATION_ERROR: Int = 5
internal const val ZZU_REMOTE_ERROR: Int = 6
internal const val ZZU_NOT_FOUND: Int = 7
internal const val ZZU_CRYPTO_ERROR: Int = 8
internal const val ZZU_RUNTIME_ERROR: Int = 9
internal const val ZZU_PANIC: Int = 10
internal const val ZZU_TIMEOUT: Int = 12
internal const val ZZU_CANCELLED: Int = 13
internal const val ZZU_UNKNOWN_METHOD: Int = 14

/** 校验链接库的 C ABI 版本，不兼容时抛出带版本号诊断的 [IllegalStateException]。 */
internal inline fun verifyAbiVersion(encodedVersion: Int, libraryVersion: () -> String) {
    val major = encodedVersion ushr ABI_VERSION_COMPONENT_BITS
    val minor = encodedVersion and ABI_VERSION_MINOR_MASK
    check(major == ABI_VERSION_MAJOR && minor >= ABI_VERSION_MINOR) {
        "libzzu ${libraryVersion()} 的 ABI 版本为 $major.$minor，" +
            "本 SDK 需要 $ABI_VERSION_MAJOR.$ABI_VERSION_MINOR 或同主版本的更高版本"
    }
}

@Serializable
private class ErrorPayload(
    val kind: String? = null,
    val message: String,
    val cause: String? = null,
    val request: RequestContext? = null,
)

/** 把状态码与错误 JSON 还原为异常；未识别的状态码按 ABI 约定视为未知错误。 */
@Suppress("CyclomaticComplexMethod") // 状态码与异常一一对应，保留完整的扁平映射便于核对 ABI。
internal fun zzuErrorOf(status: Int, payload: ByteArray): Exception {
    val error = try {
        ZzuJson.decodeFromString<ErrorPayload>(payload.decodeToString())
    } catch (_: SerializationException) {
        ErrorPayload(message = "libzzu 返回了无法解析的错误（状态码 $status）")
    }
    val message = error.message
    val detail = error.cause
    val request = error.request
    return when (status) {
        ZZU_INVALID_INPUT -> ZzuException.InvalidInput(message, detail, request)
        ZZU_TRANSPORT_ERROR -> ZzuException.Transport(
            kindOf(error.kind, TransportErrorKind.UNKNOWN),
            message,
            detail,
            request,
        )
        ZZU_HTTP_ERROR -> ZzuException.Http(message, detail, request)
        ZZU_DECODE_ERROR -> ZzuException.Decode(message, detail, request)
        ZZU_AUTHENTICATION_ERROR -> ZzuException.Authentication(
            kindOf(error.kind, AuthenticationErrorKind.UNKNOWN),
            message,
            detail,
            request,
        )
        ZZU_REMOTE_ERROR -> ZzuException.Remote(message, detail, request)
        ZZU_NOT_FOUND -> ZzuException.NotFound(message, detail, request)
        ZZU_CRYPTO_ERROR -> ZzuException.Crypto(message, detail, request)
        ZZU_RUNTIME_ERROR -> ZzuException.Runtime(message, detail, request)
        ZZU_PANIC -> ZzuException.Panic(message, detail, request)
        ZZU_TIMEOUT -> ZzuException.Timeout(message, detail, request)
        ZZU_CANCELLED -> CancellationException(message)
        ZZU_UNKNOWN_METHOD -> ZzuException.UnknownMethod(message, detail, request)
        else -> ZzuException.Unknown(message, detail, request)
    }
}

/** ABI 的 `kind` 是枚举名的小写形式，未来新增的取值落到 [fallback]。 */
private inline fun <reified E : Enum<E>> kindOf(kind: String?, fallback: E): E =
    enumEntries<E>().firstOrNull { it.name.equals(kind, ignoreCase = true) } ?: fallback

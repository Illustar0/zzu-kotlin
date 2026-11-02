package dev.zzu.internal

import dev.zzu.AuthenticationErrorKind
import dev.zzu.RequestContext
import dev.zzu.TransportErrorKind
import dev.zzu.ZzuException
import kotlin.enums.enumEntries
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

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
            kindOf(error.kind, TransportErrorKind.UNKNOWN), message, detail, request,
        )
        ZZU_HTTP_ERROR -> ZzuException.Http(message, detail, request)
        ZZU_DECODE_ERROR -> ZzuException.Decode(message, detail, request)
        ZZU_AUTHENTICATION_ERROR -> ZzuException.Authentication(
            kindOf(error.kind, AuthenticationErrorKind.UNKNOWN), message, detail, request,
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

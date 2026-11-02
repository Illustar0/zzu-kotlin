package dev.zzu

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable

/**
 * 校园服务 SDK 的统一异常，所有服务的挂起函数都以它报告失败。
 *
 * 子类与 libzzu 的错误大类一一对应，协程取消仍以 [kotlinx.coroutines.CancellationException] 传播。
 * 客户端关闭后继续使用时抛 [IllegalStateException]，属于调用方的编程错误，不归入此层次。
 *
 * @property detail 底层原因链，供诊断用。
 * @property request 触发错误的 HTTP 请求；错误与具体请求无关时为 `null`。
 */
public sealed class ZzuException(
    override val message: String,
    public val detail: String?,
    public val request: RequestContext?,
) : Exception(message) {
    override fun toString(): String = buildString {
        append(this@ZzuException::class.qualifiedName).append(": ").append(message)
        detail?.let { append(" (").append(it).append(')') }
        request?.let { append(" [").append(it).append(']') }
    }

    /** 传入的参数或配置不合法。 */
    public class InvalidInput(
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)

    /** 网络连接或底层传输失败。 */
    public class Transport(
        public val kind: TransportErrorKind,
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)

    /** 服务端返回非成功的 HTTP 状态码。 */
    public class Http(
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)

    /** 响应报文或协议数据解码失败。 */
    public class Decode(
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)

    /** 身份认证或多因素认证失败。 */
    public class Authentication(
        public val kind: AuthenticationErrorKind,
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)

    /** 远端服务返回业务级拒绝或失败结果。 */
    public class Remote(
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)

    /** 请求的目标资源（如指定学期）不存在。 */
    public class NotFound(
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)

    /** 密钥解析或加解密处理失败。 */
    public class Crypto(
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)

    /** libzzu 内部运行时初始化失败。 */
    public class Runtime(
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)

    /** libzzu 内部发生 panic，或客户端先前已因此损坏，须关闭并重建该客户端。 */
    public class Panic(
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)

    /**
     * 单次操作超过 [ZzuClientConfig.timeout]。
     *
     * 非幂等操作（如电费充值）超时后服务端可能已受理，须先查询余额再决定是否重试。
     */
    public class Timeout(
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)

    /** 链接的 libzzu 不支持该方法，通常意味着原生库比 SDK 旧。 */
    public class UnknownMethod(
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)

    /** 未归类的错误，包括比本 SDK 新的 libzzu 引入的错误大类。 */
    public class Unknown(
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)
}

/** [ZzuException.Transport] 的细分原因。 */
public enum class TransportErrorKind {
    /** 连接或读写超时。 */
    TIMEOUT,

    /** 无法建立连接，如连接被拒绝、DNS 或代理握手失败。 */
    CONNECTION,

    /** 其他传输层故障。 */
    UNKNOWN,
}

/** [ZzuException.Authentication] 的细分原因。 */
public enum class AuthenticationErrorKind {
    /** 账号或密码错误。 */
    INVALID_CREDENTIALS,

    /** 尚未登录，或会话已登出。 */
    NOT_AUTHENTICATED,

    /** 令牌已过期且无法继续使用。 */
    TOKEN_EXPIRED,

    /** 需要先完成多因素认证。 */
    MFA_REQUIRED,

    /** 多因素认证状态不满足当前操作，如未发送短信即校验验证码。 */
    INVALID_MFA_STATE,

    /** 多因素认证未通过，如短信验证码错误。 */
    MFA_VERIFICATION_FAILED,

    /** 其他认证失败。 */
    UNKNOWN,
}

/**
 * 失败的 HTTP 请求的诊断上下文。
 *
 * [responseBody] 可能含个人信息，[toString] 只输出其长度。
 *
 * @property url 已去除查询参数与凭据的 URL。
 * @property statusCode 收到的 HTTP 状态码；请求在收到响应前失败时为 `null`。
 * @property errorCode 响应中的业务错误码。
 * @property responseBody 原始响应体。
 */
@Poko
@Serializable
public class RequestContext(
    public val method: String,
    public val url: String,
    public val statusCode: Int? = null,
    public val errorCode: String? = null,
    public val responseBody: String? = null,
) {
    override fun toString(): String =
        "RequestContext(method=$method, url=$url, statusCode=$statusCode, " +
            "errorCode=$errorCode, responseBodyLength=${responseBody?.length})"
}

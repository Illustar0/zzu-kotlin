package com.illustar0.zzu

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable

/**
 * 校园服务 SDK 的统一异常，所有挂起函数与本地校验函数都通过它报告失败。
 *
 * 各子类与 `libzzu` 的错误大类一一对应。协程取消仍通过 [kotlinx.coroutines.CancellationException] 传播；
 * 在客户端调用 `close()` 之后继续使用会抛出 [IllegalStateException]，属于调用方的编程错误，不归入此层次。
 *
 * @property message 不含底层原因链与 HTTP 状态码后缀的错误主消息。
 * @property detail 底层原因链文本，无底层错误源时为 `null`，供诊断用。
 * @property request 触发错误的 HTTP 请求诊断上下文；与具体请求无关时为 `null`。
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

    /** 调用方传入的参数格式或取值不符合要求（如空字符串、越界节次、非法房间号或无效 URL）。 */
    public class InvalidInput(
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)

    /** 网络连接、读写超时或底层 HTTP 传输失败，细分原因见 [kind]。 */
    public class Transport(
        public val kind: TransportErrorKind,
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)

    /** 服务端返回非 2xx 的 HTTP 状态码。 */
    public class Http(
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)

    /** 响应正文、HTML、JSON、JSONP、`Base64URL` 或协议字段解析与校验失败。 */
    public class Decode(
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)

    /** 登录失败、未登录、令牌失效或多因素认证未通过，细分原因见 [kind]。 */
    public class Authentication(
        public val kind: AuthenticationErrorKind,
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)

    /** 远端服务正常响应，但返回了业务级拒绝或失败状态。 */
    public class Remote(
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)

    /** 请求的目标资源（如指定的 [SemesterId][com.illustar0.zzu.eas.SemesterId]）不存在。 */
    public class NotFound(
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)

    /** RSA、SM2 或 SM4 密钥解析与加解密运算失败。 */
    public class Crypto(
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)

    /** `libzzu` 内部异步运行时初始化或线程通道通信失败。 */
    public class Runtime(
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)

    /** `libzzu` 内部发生 panic，或客户端先前已在持锁期间因此损坏；须关闭并重建该客户端。 */
    public class Panic(
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)

    /**
     * 单次操作（含等待内部锁排队）超过 [ZzuClientConfig.timeout]。
     *
     * 对非幂等操作（如 [EcardClient.rechargeEnergy][com.illustar0.zzu.ecard.EcardClient.rechargeEnergy]），
     * 超时后服务端可能已受理扣款，须先查询余额或核对账单流水再决定是否重试。
     */
    public class Timeout(
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)

    /** 链接的 `libzzu` 动态库不支持该方法，通常意味着原生库版本旧于本 SDK。 */
    public class UnknownMethod(
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)

    /** 未归类的其他错误，包括比本 SDK 更新的 `libzzu` 引入的新增错误大类。 */
    public class Unknown(
        message: String,
        detail: String? = null,
        request: RequestContext? = null,
    ) : ZzuException(message, detail, request)
}

/** [ZzuException.Transport] 的细分错误类型。 */
public enum class TransportErrorKind {
    /** 连接或读写操作超时。 */
    TIMEOUT,

    /** 无法建立网络连接，如连接被拒绝、DNS 或代理握手失败。 */
    CONNECTION,

    /** 未归类的其他传输层故障。 */
    UNKNOWN,
}

/** [ZzuException.Authentication] 的细分错误类型。 */
public enum class AuthenticationErrorKind {
    /** 账号或密码错误（服务端确认凭据无效）。 */
    INVALID_CREDENTIALS,

    /** 当前客户端尚未登录或已登出。 */
    NOT_AUTHENTICATED,

    /** 令牌已过期或剩余有效期不足 15 分钟，无法继续使用。 */
    TOKEN_EXPIRED,

    /** 登录流程需要完成多因素认证（如短信验证码）后才能继续。 */
    MFA_REQUIRED,

    /** 多因素认证状态不满足当前操作前提，如未发送短信即校验验证码，或当前会话不需要/不支持短信验证。 */
    INVALID_MFA_STATE,

    /** 多因素认证校验未通过，如短信验证码错误。 */
    MFA_VERIFICATION_FAILED,

    /** 未归类的其他认证失败。 */
    UNKNOWN,
}

/**
 * 失败 HTTP 请求的诊断上下文。
 *
 * 为避免泄露敏感凭据，[url] 已预先剥离查询参数、片段与用户信息；
 * [responseBody] 可能包含个人信息，[toString] 仅输出其字符长度，不打印完整正文。
 *
 * @property method HTTP 请求方法（如 `"GET"`、`"POST"`）。
 * @property url 已脱敏的请求目标 URL（不含查询参数、片段与认证凭据）。
 * @property statusCode 收到的 HTTP 状态码；若请求在建立连接或等待响应阶段失败则为 `null`。
 * @property errorCode 从响应 JSON 中提取的业务错误码文本（如 `code`、`result` 或 `returncode` 字段）。
 * @property responseBody 原始响应体文本；在读取响应体前即失败时为 `null`。
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

package com.illustar0.zzu.cas

import com.illustar0.zzu.AuthenticationErrorKind
import com.illustar0.zzu.ZzuClientConfig
import com.illustar0.zzu.ZzuException
import com.illustar0.zzu.internal.CasTransport
import com.illustar0.zzu.internal.ZzuRpc
import com.illustar0.zzu.internal.fetch
import com.illustar0.zzu.internal.nativeCasTransport
import com.illustar0.zzu.nativeConfig
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 统一身份认证（CAS）客户端，负责会话管理、短信多因素认证、设备信任管理、个人信息与头像查询以及业务系统单点登录。
 *
 * 线程安全，实现 [AutoCloseable]，用完后须调用 [close] 释放原生句柄；关闭后已派生的
 * [EasClient][com.illustar0.zzu.eas.EasClient]、[EcardClient][com.illustar0.zzu.ecard.EcardClient]、
 * [MailClient][com.illustar0.zzu.mail.MailClient] 以及在途调用不受影响。
 * 所有挂起函数都可在主线程调用，失败时抛出 [ZzuException]；取消协程会同步取消底层 `libzzu` 调用。
 *
 * SDK 不会在后台保活，也不会在令牌失效时用缓存密码隐式重登，以免挤掉手机端官方 App 的在线会话。
 * 业务调用收到 [AuthenticationErrorKind.TOKEN_EXPIRED] 后，由应用决定何时重新调用 [login]。
 * 令牌持久化同样由应用负责：在 [login]、[reauthenticate]、[setTokens] 或 [logout] 之后读取 [tokens]
 * 存入系统安全存储；下次启动时依次调用 [setTokens] 与 [login]，令牌仍在可复用有效期内时不会发起网络请求。
 *
 * ```kotlin
 * val cas = CasClient("学号", "密码", "设备 ID")
 * cas.login { promptSmsCode() }
 * val eas = EasClient(cas)
 * ```
 */
@Suppress("TooManyFunctions") // 同一 CAS 会话的操作集中在一个客户端，保持现有公开 API。
public class CasClient internal constructor(
    internal val transport: CasTransport,
    private val rpc: ZzuRpc = ZzuRpc(transport),
) : AutoCloseable by rpc {
    /**
     * 登录统一身份认证会话。
     *
     * 若已缓存距离过期超过 15 分钟的 [Tokens.userToken] 且 [Tokens.refreshToken] 尚未过期，
     * 会直接复用现有令牌并激活本地会话，不发起网络请求；否则自动探测 MFA 状态、获取服务端 RSA 公钥并加密凭据完成密码登录。
     *
     * @throws ZzuException.Authentication 需要多因素认证时 `kind` 为 [AuthenticationErrorKind.MFA_REQUIRED]，
     * 可先调用 [prepareSms] 预览脱敏手机号，再依次调用 [sendSms]、[verifySms] 与 [login]
     * （或直接改用接受回调的 [login] 重载）；
     * 账号或密码错误时 `kind` 为 [AuthenticationErrorKind.INVALID_CREDENTIALS]；
     * 服务端签发的令牌已过期时 `kind` 为 [AuthenticationErrorKind.TOKEN_EXPIRED]。
     * @throws ZzuException.Transport 网络传输失败时。
     * @throws ZzuException.Http 服务端返回非 2xx 状态码时。
     * @throws ZzuException.Decode 公钥或响应报文解析失败时。
     * @throws ZzuException.Crypto RSA 加密失败时。
     */
    public suspend fun login() {
        rpc.perform("login")
    }

    /**
     * 登录统一身份认证会话；需要多因素认证时自动发送短信，并将 [SmsChallenge] 传给 [smsCode] 以索取验证码。
     *
     * 当无参数 [login] 抛出 `kind` 为 [AuthenticationErrorKind.MFA_REQUIRED] 的 [ZzuException.Authentication] 时，
     * 会依次执行 [sendSms]、调用 [smsCode]、执行 [verifySms]，随后再调用 [login] 完成签发；
     * 其他异常直接向外抛出。[smsCode] 只在需要多因素认证且短信发出后调用。
     *
     * ```kotlin
     * cas.login { challenge -> smsDialog.awaitCode(challenge.securePhone) }
     * ```
     */
    public suspend fun login(smsCode: suspend (SmsChallenge) -> String) {
        try {
            login()
        } catch (exception: ZzuException.Authentication) {
            if (exception.kind != AuthenticationErrorKind.MFA_REQUIRED) throw exception
            val challenge = sendSms()
            verifySms(smsCode(challenge))
            login()
        }
    }

    /**
     * 丢弃已缓存的令牌与多因素认证状态，强制重新执行完整的密码认证。
     *
     * 错误条件同无参数 [login]。
     */
    public suspend fun reauthenticate() {
        rpc.perform("reauthenticate")
    }

    /**
     * 清除本地缓存的令牌、多因素认证状态与登录标记（不发起网络请求）。
     *
     * 派生的 [EasClient][com.illustar0.zzu.eas.EasClient]、[EcardClient][com.illustar0.zzu.ecard.EcardClient] 与
     * [MailClient][com.illustar0.zzu.mail.MailClient] 仍保留各自的本地缓存（如 `isLoggedIn()`、`currentSemester()`），
     * 但此后触发需要 CAS 令牌的操作时会抛出 [AuthenticationErrorKind.NOT_AUTHENTICATED]。
     */
    public suspend fun logout() {
        rpc.perform("logout")
    }

    /** 返回当前会话是否已完成登录。 */
    public suspend fun isLoggedIn(): Boolean = rpc.fetch("is_logged_in")

    /** 返回创建客户端时配置的设备 ID。 */
    public suspend fun deviceId(): String = rpc.fetch("device_id")

    /**
     * 预置外部持久化的令牌对 [tokens]，并重置本地登录态与多因素认证状态。
     *
     * 本方法只校验并缓存令牌，不发起网络请求；预置后仍须调用 [login] 激活会话，
     * 令牌仍在可复用有效期内时 [login] 会直接复用，无需密码登录。
     *
     * @throws ZzuException.InvalidInput `userToken` 或 `refreshToken` 不是合法 JWT，或任一令牌已过期时。
     */
    public suspend fun setTokens(tokens: Tokens) {
        rpc.perform(
            "set_tokens",
            buildJsonObject {
                put("userToken", tokens.userToken)
                put("refreshToken", tokens.refreshToken)
            },
        )
    }

    /** 返回当前会话缓存的令牌对副本；未设置且未登录时为 `null`。 */
    public suspend fun tokens(): Tokens? = rpc.fetch("tokens")

    /**
     * 在本地校验并返回当前已登录会话的可用用户令牌。
     *
     * 本方法不发起网络请求，也不会用密码隐式重登。
     *
     * @throws ZzuException.Authentication 尚未登录或已登出时 `kind` 为 [AuthenticationErrorKind.NOT_AUTHENTICATED]；
     * `userToken` 剩余有效期不足 15 分钟、`refreshToken` 已过期或无法解析 JWT 过期时间时为
     * [AuthenticationErrorKind.TOKEN_EXPIRED]。
     */
    public suspend fun userToken(): String = rpc.fetch("user_token")

    /**
     * 向服务端探测当前账号与设备是否需要多因素认证，并更新内部 MFA 状态。
     *
     * 每次调用都会重置未完成的 MFA 状态，可在验证会话过期后用于重新开启一轮验证。
     *
     * @throws ZzuException.Transport 网络传输失败时。
     * @throws ZzuException.Http 服务端返回非 2xx 状态码时。
     * @throws ZzuException.Decode 公钥或响应 MFA 字段解析失败时。
     * @throws ZzuException.Crypto RSA 加密失败时。
     * @throws ZzuException.Authentication 认证探测被服务端拒绝时。
     */
    public suspend fun detectMfa(): MfaStatus = rpc.fetch("detect_mfa")

    /**
     * 初始化短信多因素认证会话并返回发送上下文（含脱敏安全手机号），不发送短信。
     *
     * 该步骤可选，适合先在界面展示脱敏手机号，再调用 [sendSms]。
     * 若此前尚未探测 MFA 状态，会自动先探测一次；同一轮验证内重复调用会直接返回缓存的上下文。
     * 收到服务端拒绝或无效响应时会自动清空 MFA 状态。
     *
     * @throws ZzuException.Authentication 当前会话不需要多因素认证或不支持短信验证时 `kind` 为
     * [AuthenticationErrorKind.INVALID_MFA_STATE]；探测或初始化被服务端拒绝时为其他认证错误。
     * @throws ZzuException.Transport 网络传输失败时。
     * @throws ZzuException.Http 服务端返回非 2xx 状态码时。
     * @throws ZzuException.Decode 响应解析失败时。
     */
    public suspend fun prepareSms(): SmsChallenge = rpc.fetch("prepare_sms")

    /**
     * 请求服务端向绑定的安全手机发送短信验证码，并返回发送上下文（含脱敏安全手机号）。
     *
     * 若此前尚未探测或初始化短信 MFA 会话，会自动先完成 [prepareSms]。
     * 短信发送失败时（如触发频控冷却）会保留当前 MFA 会话，避免作废已发出的验证码；
     * 若会话已过期，可调用 [detectMfa] 重新开启一轮验证。
     *
     * @throws ZzuException.Authentication 当前会话不需要多因素认证或不支持短信验证时 `kind` 为
     * [AuthenticationErrorKind.INVALID_MFA_STATE]；初始化或发送过程被拒绝时为其他认证错误。
     * @throws ZzuException.Transport 网络传输失败时。
     * @throws ZzuException.Http 服务端返回非 2xx 状态码时。
     * @throws ZzuException.Decode 响应解析失败时。
     */
    public suspend fun sendSms(): SmsChallenge = rpc.fetch("send_sms")

    /**
     * 校验短信验证码 [code] 以完成多因素认证；校验通过后须再次调用 [login] 完成令牌签发。
     *
     * 验证码不匹配时会保留当前 MFA 会话，可直接重试；服务端返回非 2xx 或业务失败（如验证会话已过期）时会自动清空 MFA 状态。
     *
     * @throws ZzuException.InvalidInput [code] 为空字符串时。
     * @throws ZzuException.Authentication 尚未调用 [sendSms] 时 `kind` 为 [AuthenticationErrorKind.INVALID_MFA_STATE]；
     * 验证码错误或校验未通过时为 [AuthenticationErrorKind.MFA_VERIFICATION_FAILED]。
     * @throws ZzuException.Transport 网络传输失败时。
     * @throws ZzuException.Decode 响应解析失败时。
     */
    public suspend fun verifySms(code: String) {
        rpc.perform("verify_sms", buildJsonObject { put("code", code) })
    }

    /**
     * 更新当前账号下指定设备 [deviceId] 的信任状态，需已登录。
     *
     * 受信任设备登录时无需多因素认证；[isTrusted] 为 `true` 表示信任该设备，为 `false` 表示取消信任。
     *
     * @throws ZzuException.InvalidInput [deviceId] 为空字符串时。
     * @throws ZzuException.Authentication 尚未登录或令牌不可用时（`NOT_AUTHENTICATED` 或 `TOKEN_EXPIRED`）。
     * @throws ZzuException.Remote 服务端拒绝更新设备信任状态时。
     * @throws ZzuException.Decode 响应缺少有效的 `acknowleged` 字段或解析失败时。
     * @throws ZzuException.Transport 网络传输失败时。
     * @throws ZzuException.Http 服务端返回非 2xx 状态码时。
     */
    public suspend fun setDeviceTrust(deviceId: String, isTrusted: Boolean) {
        rpc.perform(
            "set_device_trust",
            buildJsonObject {
                put("deviceId", deviceId)
                put("isTrusted", isTrusted)
            },
        )
    }

    /**
     * 分页查询当前账号下的历史登录设备与信任状态，需已登录。
     *
     * [page] 从 1 开始，默认为 1；[pageSize] 为 `null` 时使用 [DevicePage.DEFAULT_PAGE_SIZE]（`20`）。
     * 返回条目的 [LoginDevice.deviceId] 可直接传给 [setDeviceTrust] 更新信任状态。
     *
     * @throws ZzuException.InvalidInput [page] 小于 1 或 [pageSize] 小于 1 时。
     * @throws ZzuException.Authentication 尚未登录或令牌不可用时（`NOT_AUTHENTICATED` 或 `TOKEN_EXPIRED`）。
     * @throws ZzuException.Remote 认证服务返回业务错误状态时。
     * @throws ZzuException.Decode 设备记录字段或时间格式解析失败时。
     * @throws ZzuException.Transport 网络传输失败时。
     * @throws ZzuException.Http 服务端返回非 2xx 状态码时。
     */
    public suspend fun devices(
        page: Int = 1,
        pageSize: Int? = null,
    ): DevicePage = rpc.fetch(
        "devices",
        buildJsonObject {
            put("page", page)
            pageSize?.let { put("pageSize", it) }
        },
    )

    /**
     * 查询当前用户的个人身份信息，需已登录。
     *
     * @throws ZzuException.Authentication 尚未登录或令牌不可用时（`NOT_AUTHENTICATED` 或 `TOKEN_EXPIRED`）。
     * @throws ZzuException.Transport 网络传输失败时。
     * @throws ZzuException.Http 服务端返回非 2xx 状态码时。
     * @throws ZzuException.Remote 认证服务返回业务错误状态时。
     * @throws ZzuException.Decode 个人信息结构不符时。
     */
    public suspend fun userInfo(): PersonalInfo = rpc.fetch("user_info")

    /**
     * 查询当前用户在个人中心的头像图像，需已登录；当前账号未设置头像时返回 `null`。
     *
     * @throws ZzuException.Authentication 尚未登录或令牌不可用时（`NOT_AUTHENTICATED` 或 `TOKEN_EXPIRED`）。
     * @throws ZzuException.Remote 认证服务返回业务错误状态时。
     * @throws ZzuException.Decode 响应不是受支持的图像格式或载荷解析失败时。
     * @throws ZzuException.Transport 网络传输失败时。
     * @throws ZzuException.Http 服务端返回非成功状态码时。
     */
    public suspend fun portrait(): Portrait? = rpc.fetch("portrait")

    /**
     * 获取当前账号访问指定业务系统 [service] 的单点登录跳转 URL，需已登录。
     *
     * - [SsoService.PRESIDENT_MAILBOX]、[SsoService.ECARD] 与 [SsoService.UndergradEas]：
     *   直接在本地用用户令牌拼接带 `token` 或 `idToken` 参数的目标地址，不发起网络请求。
     * - 其余服务：携带用户令牌请求对应门户入口或 CAS 票据签发接口，不跟随重定向，直接提取响应头中的 `Location` 地址。
     *
     * 返回的 URL 携带单点登录票据或用户令牌，切勿写入日志。
     *
     * @throws ZzuException.Authentication 尚未登录或令牌不可用时 `kind` 为
     * [AuthenticationErrorKind.NOT_AUTHENTICATED] 或 [AuthenticationErrorKind.TOKEN_EXPIRED]。
     * @throws ZzuException.Transport 网络传输失败时。
     * @throws ZzuException.Http 服务端返回非 2xx/3xx 状态码时。
     * @throws ZzuException.Decode 重定向响应缺少非空 `Location` 头或目标 URL 构造失败时。
     */
    public suspend fun serviceSsoUrl(service: SsoService): String =
        rpc.fetch("service_sso_url", buildJsonObject { put("service", service.wire) })
}

/**
 * 创建统一身份认证客户端。
 *
 * 仅在本地初始化账号 [account]、密码 [password]、设备标识 [deviceId] 与可选配置 [configure]，
 * 不发起网络请求；实际认证须随后调用 [CasClient.login]。
 *
 * [deviceId] 可以是任意包含非空白字符的字符串，但本质上是认证凭据，应保证唯一且难以预测；
 * 密码登录与多因素认证探测都会上报该值。
 *
 * @throws ZzuException.InvalidInput [deviceId] 为空或仅含空白字符时。
 * @throws ZzuException 运行时初始化或配置校验失败时。
 */
public fun CasClient(
    account: String,
    password: String,
    deviceId: String,
    configure: ZzuClientConfig.() -> Unit = {},
): CasClient {
    val config = nativeConfig(configure) {
        put("account", account)
        put("password", password)
        put("deviceId", deviceId)
    }
    return CasClient(nativeCasTransport(config))
}

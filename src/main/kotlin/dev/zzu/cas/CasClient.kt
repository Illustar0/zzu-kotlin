package dev.zzu.cas

import dev.zzu.AuthenticationErrorKind
import dev.zzu.ZzuClientConfig
import dev.zzu.ZzuException
import dev.zzu.internal.CasTransport
import dev.zzu.internal.ZzuRpc
import dev.zzu.internal.fetch
import dev.zzu.internal.nativeCasTransport
import dev.zzu.nativeConfig
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 统一身份认证（CAS）客户端，线程安全，用完后须 [close] 释放句柄。
 * 已派生的教务与校园卡客户端以及在途调用不受影响。
 *
 * 所有挂起函数都可在主线程调用，失败时抛 [ZzuException]，协程取消会传递到 libzzu。
 *
 * SDK 不会在后台保活，也不会在令牌失效时用密码隐式重登：业务调用收到
 * [AuthenticationErrorKind.TOKEN_EXPIRED] 后，由应用决定何时重新 [login]。
 * 令牌的持久化同样由应用负责：[login]、[reauthenticate]、[setTokens]、[logout] 之后读取 [tokens]
 * 存入安全存储；下次启动依次调用 [setTokens] 与 [login]，令牌可复用时不发起网络请求。
 *
 * ```kotlin
 * val cas = CasClient("学号", "密码")
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
     * 登录 CAS 会话；已缓存可复用令牌时直接激活，不发起网络请求。
     *
     * @throws ZzuException.Authentication 需要多因素认证时 `kind` 为
     * [AuthenticationErrorKind.MFA_REQUIRED]，完成 [sendSms] 与 [verifySms] 后再次调用；
     * 也可以改用接受验证码的 [login] 重载。
     */
    public suspend fun login() {
        rpc.perform("login")
    }

    /**
     * 登录 CAS 会话，需要多因素认证时自动发送短信，并向 [smsCode] 索取验证码。
     *
     * ```kotlin
     * cas.login { smsDialog.awaitCode() }
     * ```
     *
     * @param smsCode 只在需要多因素认证时调用，短信已发出。
     */
    public suspend fun login(smsCode: suspend () -> String) {
        try {
            login()
        } catch (exception: ZzuException.Authentication) {
            if (exception.kind != AuthenticationErrorKind.MFA_REQUIRED) throw exception
            sendSms()
            verifySms(smsCode())
            login()
        }
    }

    /** 清除本地令牌与多因素认证状态，强制重新执行完整的密码认证；可能抛出与 [login] 相同的错误。 */
    public suspend fun reauthenticate() {
        rpc.perform("reauthenticate")
    }

    /** 清空本地会话；共享此会话的教务与校园卡客户端此后会收到“未登录”错误。 */
    public suspend fun logout() {
        rpc.perform("logout")
    }

    /** 返回当前会话是否已登录。 */
    public suspend fun isLoggedIn(): Boolean = rpc.fetch("is_logged_in")

    /**
     * 预置持久化的令牌对并重置登录态，之后仍须调用 [login] 激活会话。
     *
     * @throws ZzuException.InvalidInput 令牌格式非法或已过期时。
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

    /** 返回当前缓存的令牌对；未设置且未登录时为 `null`。 */
    public suspend fun tokens(): Tokens? = rpc.fetch("tokens")

    /**
     * 本地校验并返回可用的用户令牌。
     *
     * @throws ZzuException.Authentication 未登录时 `kind` 为
     * [AuthenticationErrorKind.NOT_AUTHENTICATED]；令牌剩余有效期不足 15 分钟时为
     * [AuthenticationErrorKind.TOKEN_EXPIRED]。
     */
    public suspend fun userToken(): String = rpc.fetch("user_token")

    /** 设置登录与多因素认证检测时上报的设备标识（不得为空），同时重置未完成的多因素认证。 */
    public suspend fun setDevice(deviceId: String) {
        rpc.perform("set_device", buildJsonObject { put("deviceId", deviceId) })
    }

    /** 向服务端探测当前账号与设备是否需要多因素认证。 */
    public suspend fun detectMfa(): MfaStatus = rpc.fetch("detect_mfa")

    /** 向绑定的安全手机发送短信验证码。 */
    public suspend fun sendSms() {
        rpc.perform("send_sms")
    }

    /** 提交短信验证码，通过后须再次调用 [login]。 */
    public suspend fun verifySms(code: String) {
        rpc.perform("verify_sms", buildJsonObject { put("code", code) })
    }

    /** 查询个人信息，需已登录。 */
    public suspend fun userInfo(): PersonalInfo = rpc.fetch("user_info")

    /**
     * 获取 [service] 的单点登录跳转地址，需已登录。
     * 本科教务子应用的地址由 libzzu 在本地使用用户令牌拼接 `idToken` 参数，不发起网络请求；
     * 其余服务的地址取自服务端重定向响应的 `Location` 头，原样返回。
     * 返回的 URL 携带登录票据或用户令牌，切勿写入日志。
     *
     * @throws ZzuException.Authentication 未登录时 `kind` 为
     * [AuthenticationErrorKind.NOT_AUTHENTICATED]；令牌不再可用时为
     * [AuthenticationErrorKind.TOKEN_EXPIRED]。
     * @throws ZzuException.Decode 响应缺少非空的 `Location` 头时。
     */
    public suspend fun serviceLoginUrl(service: SsoService): String =
        rpc.fetch("service_login_url", buildJsonObject { put("service", service.wire) })
}

/**
 * 创建 CAS 客户端，不会发起网络请求。
 *
 * @throws ZzuException 配置不被 libzzu 接受时。
 */
public fun CasClient(
    account: String,
    password: String,
    configure: ZzuClientConfig.() -> Unit = {},
): CasClient {
    val config = nativeConfig(configure) {
        put("account", account)
        put("password", password)
    }
    return CasClient(nativeCasTransport(config))
}

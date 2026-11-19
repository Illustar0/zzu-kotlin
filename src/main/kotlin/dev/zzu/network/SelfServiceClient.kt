package dev.zzu.network

import dev.zzu.ZzuClientConfig
import dev.zzu.ZzuException
import dev.zzu.internal.ZzuRpc
import dev.zzu.internal.fetch
import dev.zzu.internal.nativeSelfServiceTransport
import dev.zzu.nativeConfig
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 校园网自助服务系统（`/Self`）客户端，用于查询在线终端并强制指定设备下线。
 *
 * 不依赖 CAS 账号，内部独立维护会话 Cookie。线程安全，实现 [AutoCloseable]，用完后须调用 [close] 释放句柄，
 * 在途调用不受影响；查询与下线操作（[isLoggedIn]、[onlineDevices]、[kickDevice]）可并发执行，
 * [login] 与 [logout] 与之互斥同步。
 *
 * 除 [login]、[isLoggedIn] 外的方法都要求先完成 [login]。
 * 所有挂起函数都可在主线程调用，失败时抛出 [ZzuException]；取消协程会同步取消底层 `libzzu` 调用。
 */
public class SelfServiceClient internal constructor(private val rpc: ZzuRpc) : AutoCloseable by rpc {
    /**
     * 登录校园网自助服务系统。
     *
     * @throws ZzuException.Decode 登录页缺少 `checkcode` 表单字段时。
     * @throws ZzuException.Authentication 提交后未跳转至控制台页面（如账号或密码错误）时。
     * @throws ZzuException.Transport 网络传输失败时。
     * @throws ZzuException.Http 服务端返回非 2xx 状态码时。
     */
    public suspend fun login(account: String, password: String) {
        rpc.perform(
            "login",
            buildJsonObject {
                put("account", account)
                put("password", password)
            },
        )
    }

    /** 返回自助服务客户端是否已完成登录。 */
    public suspend fun isLoggedIn(): Boolean = rpc.fetch("is_logged_in")

    /**
     * 获取当前账号下的全部在线终端列表。
     *
     * @throws ZzuException.Authentication 尚未调用 [login] 时 `kind` 为
     * [AuthenticationErrorKind.NOT_AUTHENTICATED][dev.zzu.AuthenticationErrorKind.NOT_AUTHENTICATED]。
     * @throws ZzuException.Decode 设备列表 JSON 解析失败时。
     * @throws ZzuException.Transport 网络传输失败时。
     */
    public suspend fun onlineDevices(): List<OnlineDevice> = rpc.fetch("online_devices")

    /**
     * 根据在线设备的 [sessionId] 强制指定终端下线。
     *
     * @throws ZzuException.Authentication 尚未调用 [login] 时 `kind` 为
     * [AuthenticationErrorKind.NOT_AUTHENTICATED][dev.zzu.AuthenticationErrorKind.NOT_AUTHENTICATED]。
     * @throws ZzuException.InvalidInput [SessionId.value] 为空字符串时。
     * @throws ZzuException.Transport 网络传输失败时。
     * @throws ZzuException.Http 服务端返回非 2xx 状态码时。
     */
    public suspend fun kickDevice(sessionId: SessionId) {
        rpc.perform("kick_device", buildJsonObject { put("sessionId", sessionId.value) })
    }

    /**
     * 登出远端自助服务会话并重置本地登录状态。
     *
     * 即使远端登出请求失败，仍会清除本地登录标记。
     *
     * @throws ZzuException.Authentication 尚未调用 [login] 时 `kind` 为
     * [AuthenticationErrorKind.NOT_AUTHENTICATED][dev.zzu.AuthenticationErrorKind.NOT_AUTHENTICATED]。
     * @throws ZzuException.Transport 网络传输失败时。
     * @throws ZzuException.Http 服务端返回非 2xx 状态码时。
     */
    public suspend fun logout() {
        rpc.perform("logout")
    }
}

/**
 * 创建校园网自助服务客户端。
 *
 * 仅在本地校验根地址 [baseUrl] 与可选配置 [configure]，不会发起网络请求。
 *
 * @throws ZzuException.InvalidInput [baseUrl] 不是有效的 `http` 或 `https` URL 时。
 */
public fun SelfServiceClient(
    baseUrl: String,
    configure: ZzuClientConfig.() -> Unit = {},
): SelfServiceClient {
    val config = nativeConfig(configure) { put("baseUrl", baseUrl) }
    return SelfServiceClient(ZzuRpc(nativeSelfServiceTransport(config)))
}

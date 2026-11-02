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
 * 校园网自助服务客户端，线程安全，用完后须 [close] 释放句柄，在途调用不受影响。
 *
 * 不依赖 CAS 账号。除 [login]、[isLoggedIn] 外的方法都要求先完成 [login]。所有挂起函数都可在
 * 主线程调用，失败时抛 [ZzuException]，协程取消会传递到 libzzu。
 */
public class SelfServiceClient internal constructor(private val rpc: ZzuRpc) : AutoCloseable by rpc {
    /** 登录校园网自助服务系统。 */
    public suspend fun login(account: String, password: String) {
        rpc.perform(
            "login",
            buildJsonObject {
                put("account", account)
                put("password", password)
            },
        )
    }

    /** 返回是否已登录。 */
    public suspend fun isLoggedIn(): Boolean = rpc.fetch("is_logged_in")

    /** 查询当前账号下的全部在线终端。 */
    public suspend fun onlineDevices(): List<OnlineDevice> = rpc.fetch("online_devices")

    /** 强制指定终端下线。 */
    public suspend fun kickDevice(sessionId: SessionId) {
        rpc.perform("kick_device", buildJsonObject { put("sessionId", sessionId.value) })
    }

    /** 登出远端会话并清除本地登录标记。 */
    public suspend fun logout() {
        rpc.perform("logout")
    }
}

/**
 * 创建自助服务客户端，不会发起网络请求。
 *
 * @param baseUrl 自助服务根地址，须为 `http` 或 `https` 地址。
 * @throws ZzuException.InvalidInput 地址无效时。
 */
public fun SelfServiceClient(
    baseUrl: String,
    configure: ZzuClientConfig.() -> Unit = {},
): SelfServiceClient {
    val config = nativeConfig(configure) { put("baseUrl", baseUrl) }
    return SelfServiceClient(ZzuRpc(nativeSelfServiceTransport(config)))
}

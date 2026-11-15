package dev.zzu.network

import dev.zzu.ZzuClientConfig
import dev.zzu.ZzuException
import dev.zzu.internal.ZzuRpc
import dev.zzu.internal.fetch
import dev.zzu.internal.nativeDiscoverPortal
import dev.zzu.internal.nativePortalTransport
import dev.zzu.internal.putValue
import dev.zzu.nativeConfig
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * 校园网 Portal 认证客户端，线程安全，支持并发调用，用完后须 [close] 释放句柄，在途调用不受影响。
 *
 * 不依赖 CAS 账号。挂起函数都可在主线程调用，失败时抛 [ZzuException]，协程取消会传递到 libzzu。
 *
 * ```kotlin
 * PortalClient(discoverPortal()).use { portal ->
 *     val auth = portal.login("学号", "密码")
 *     check(auth.isSuccess) { auth.message }
 * }
 * ```
 */
public class PortalClient internal constructor(private val rpc: ZzuRpc) : AutoCloseable by rpc {
    /**
     * 发起终端上线认证。
     *
     * 请求成功不代表认证通过，须检查 [PortalAuthResult.isSuccess]。
     *
     * @param account 融合宽带账号须自带运营商后缀，如 `@cmcc`、`@unicom`、`@telecom`。
     */
    public suspend fun login(
        account: String,
        password: String,
        encryption: PortalEncryption = PortalEncryption.PLAIN,
    ): PortalAuthResult = rpc.fetch(
        "login",
        buildJsonObject {
            put("account", account)
            put("password", password)
            putValue("encryption", encryption)
        },
    )
}

/**
 * 创建 Portal 客户端，不会发起网络请求。
 *
 * @param baseUrl 含主机名的 `http` 或 `https` 地址，通常取 [PortalInfo.portalServerUrl]。
 * @param userIp 门户识别的终端 IP，登录时作为终端地址发送，通常取 [PortalInfo.userIp]。
 * @param sourceAddress 是否把 [userIp] 绑定为本地源地址；选择 [PortalSourceAddress.USER_IP]
 * 而 [userIp] 不是本机网卡地址时，[PortalClient.login] 会抛 [ZzuException.Transport]。
 * @throws ZzuException.InvalidInput 地址无效时。
 */
public fun PortalClient(
    baseUrl: String,
    userIp: String,
    sourceAddress: PortalSourceAddress = PortalSourceAddress.USER_IP,
    configure: ZzuClientConfig.() -> Unit = {},
): PortalClient {
    val config = nativeConfig(configure) {
        put("baseUrl", baseUrl)
        put("userIp", userIp)
        putValue("sourceAddress", sourceAddress)
    }
    return PortalClient(ZzuRpc(nativePortalTransport(config)))
}

/** 用 [discoverPortal] 的结果创建 Portal 客户端，不会发起网络请求；[sourceAddress] 与接受 `baseUrl` 的重载相同。 */
public fun PortalClient(
    info: PortalInfo,
    sourceAddress: PortalSourceAddress = PortalSourceAddress.USER_IP,
    configure: ZzuClientConfig.() -> Unit = {},
): PortalClient = PortalClient(info.portalServerUrl, info.userIp, sourceAddress, configure)

/**
 * 探测当前网络下的校园网 Portal 地址与门户识别的终端 IP。
 *
 * @throws ZzuException.Remote 当前网络已连通互联网、未触发门户重定向时。
 */
public suspend fun discoverPortal(configure: ZzuClientConfig.() -> Unit = {}): PortalInfo =
    nativeDiscoverPortal(nativeConfig(configure), serializer<PortalInfo>())

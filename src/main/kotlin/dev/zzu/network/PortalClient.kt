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
 * 校园网 `eportal` 终端上线认证客户端。
 *
 * 不依赖 CAS 账号。无可变会话状态，线程安全，实现 [AutoCloseable]，支持并发调用；用完后须调用 [close] 释放句柄，
 * 在途调用不受影响。所有挂起函数都可在主线程调用，失败时抛出 [ZzuException]；取消协程会同步取消底层 `libzzu` 调用。
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
     * 向校园网 Portal 服务器发起终端上线认证请求（只连接 IPv4 地址）。
     *
     * 融合宽带用户须在 [account] 后自行附带运营商后缀（如中国移动 `@cmcc`、中国联通 `@unicom`、
     * 中国电信 `@telecom`）；普通校园网账号直接传入学号或工号即可。
     * 本函数正常返回只代表收到服务端 JSONP 响应，不代表认证通过，须检查 [PortalAuthResult.isSuccess]。
     *
     * @throws ZzuException.InvalidInput 选择 [PortalEncryption.XOR] 且任一待编码字段超过 512 个字符时。
     * @throws ZzuException.Transport 选择 [PortalSourceAddress.USER_IP] 而 `userIp` 无法绑定为本机源地址，或网络传输失败时。
     * @throws ZzuException.Http 服务端返回非 2xx 状态码时。
     * @throws ZzuException.Decode 返回的 JSONP 报文无法解析时。
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
 * 创建校园网 `eportal` 认证客户端。
 *
 * 仅在本地校验配置，不会发起网络请求或立即绑定本地套接字；登录请求只连接 IPv4 地址。
 *
 * @param baseUrl 含主机名的有效 `http` 或 `https` 地址，通常取 [PortalInfo.portalServerUrl]。
 * @param userIp 门户识别的终端 IP，登录时作为终端地址发送，通常取 [PortalInfo.userIp]。
 * @param sourceAddress 是否将 [userIp] 绑定为本地套接字源地址；选择 [PortalSourceAddress.USER_IP]
 * 而 [userIp] 不是本机可用网卡地址时，调用 [PortalClient.login] 将抛出 [ZzuException.Transport]。
 * @throws ZzuException.InvalidInput [baseUrl] 不是含主机名的有效 `http`/`https` URL 或 [userIp] 无效时。
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

/**
 * 使用 [discoverPortal] 返回的 [info] 创建 [PortalClient]，不发起网络请求。
 *
 * 参数 [sourceAddress]、[configure] 与异常行为同接受 `baseUrl` 的 [PortalClient] 重载。
 */
public fun PortalClient(
    info: PortalInfo,
    sourceAddress: PortalSourceAddress = PortalSourceAddress.USER_IP,
    configure: ZzuClientConfig.() -> Unit = {},
): PortalClient = PortalClient(info.portalServerUrl, info.userIp, sourceAddress, configure)

/**
 * 发起明文 HTTP 探测，自动发现当前网络下的校园网 Portal 地址与门户识别的终端 IP。
 *
 * 探测请求只走 IPv4，避免因系统经其他网口的 IPv6 默认路由发包而测得错误的终端地址。
 *
 * @throws ZzuException.Remote 当前网络已连通互联网、未触发门户认证重定向时。
 * @throws ZzuException.Decode HTML 跳转链接缺失，或重定向 URL 缺少合法的客户端 IP 参数时。
 * @throws ZzuException.Transport 网络传输失败时。
 * @throws ZzuException.Http 探测请求返回非 2xx 状态码时。
 */
public suspend fun discoverPortal(configure: ZzuClientConfig.() -> Unit = {}): PortalInfo =
    nativeDiscoverPortal(nativeConfig(configure), serializer<PortalInfo>())

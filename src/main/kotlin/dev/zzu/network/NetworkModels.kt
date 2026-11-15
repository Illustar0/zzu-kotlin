package dev.zzu.network

import dev.drewhamilton.poko.Poko
import dev.zzu.ZzuTimeZone
import dev.zzu.internal.SecondsSerializer
import dev.zzu.internal.ZonedInstantSerializer
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 探测到的校园网 Portal 参数。
 *
 * @property authUrl Portal 认证页根地址。
 * @property portalServerUrl `eportal` 登录接口地址。
 * @property userIp 门户识别的终端 IP；终端位于 NAT 路由器后时为路由器 WAN 口地址。
 */
@Poko
@Serializable
public class PortalInfo(
    public val authUrl: String,
    public val portalServerUrl: String,
    public val userIp: String,
)

/** Portal 登录请求的本地源地址选择方式。 */
@Serializable
public enum class PortalSourceAddress {
    /** 把 `userIp` 绑定为本地源地址，适用于终端直接接入校园网。 */
    @SerialName("user_ip")
    USER_IP,

    /** 由系统路由选择源地址，适用于终端位于 NAT 路由器后、`userIp` 为路由器 WAN 口地址。 */
    @SerialName("system")
    SYSTEM,
}

/** Portal 登录请求的编码方式。 */
@Serializable
public enum class PortalEncryption {
    /** 明文传输，密码只做 Base64 编码。 */
    @SerialName("plain")
    PLAIN,

    /** 用客户端 IP 异或编码，待编码字段不得超过 512 个字符。 */
    @SerialName("xor")
    XOR,
}

/**
 * Portal 认证结果。
 *
 * @property isSuccess 终端是否认证通过。
 * @property result 服务端结果码。
 * @property message 服务端状态说明。
 * @property retCode 认证失败时的细分返回码。
 */
@Poko
@Serializable
public class PortalAuthResult(
    public val isSuccess: Boolean,
    public val result: Long,
    public val message: String,
    public val retCode: Long? = null,
)

/** 在线会话 ID。 */
@JvmInline
@Serializable
public value class SessionId(public val value: String)

/**
 * 校园网在线终端。
 *
 * @property brasId 宽带远程接入服务器标识。
 * @property downFlow 已用下行流量文本。
 * @property hostName 终端主机名，未上报时为空字符串。
 * @property loginTime 上线时刻；用 [ZzuTimeZone] 换算成当地时间。
 * @property upFlow 已用上行流量文本。
 * @property onlineDuration 已在线时长。
 * @property userId 校园网账户 ID。
 */
@Poko
@Serializable
@Suppress("LongParameterList") // 构造参数与 libzzu 的在线终端 JSON 字段一一对应。
public class OnlineDevice(
    @SerialName("brasid")
    public val brasId: String,
    public val downFlow: String,
    public val hostName: String = "",
    public val ip: String,
    @Serializable(with = ZonedInstantSerializer::class)
    public val loginTime: Instant,
    public val mac: String,
    public val sessionId: SessionId,
    public val terminalType: String,
    public val upFlow: String,
    @SerialName("useTime")
    @Serializable(with = SecondsSerializer::class)
    public val onlineDuration: Duration,
    public val userId: Long,
)

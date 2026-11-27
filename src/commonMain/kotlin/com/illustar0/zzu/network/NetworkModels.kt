package com.illustar0.zzu.network

import com.illustar0.zzu.ZzuTimeZone
import com.illustar0.zzu.internal.SecondsSerializer
import com.illustar0.zzu.internal.ZonedInstantSerializer
import dev.drewhamilton.poko.Poko
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 自动探测得到的校园网 Portal 地址与终端 IP 参数。
 *
 * @property authUrl Portal 认证页面根地址，用于静态脚本与门户资源。
 * @property portalServerUrl `eportal` 登录接口服务地址，包含探测到的协议与端口（如 801 或 802）。
 * @property userIp 门户重定向参数中识别的终端 IP 地址；终端位于 NAT 路由器后时为路由器 WAN 口地址。
 */
@Poko
@Serializable
public class PortalInfo(
    public val authUrl: String,
    public val portalServerUrl: String,
    public val userIp: String,
)

/**
 * 校园网 Portal 页面提供的运营商选项。
 *
 * @property name 运营商显示名称，如“校园网”“中国移动”。
 * @property suffix 附加在登录账号后的运营商后缀，如 `"@cmcc"`；普通校园网通常为空字符串 `""`。
 */
@Poko
@Serializable
public class IspInfo(
    public val name: String,
    public val suffix: String,
)

/** Portal 请求的本地源地址选择方式。 */
@Serializable
public enum class PortalSourceAddress {
    /** 将 `userIp` 绑定为本地套接字源地址，适合终端直连校园网、`userIp` 就是本机网卡地址的场景。 */
    @SerialName("user_ip")
    USER_IP,

    /** 由系统路由自动选择源地址，适合终端位于 NAT 路由器后、`userIp` 为路由器 WAN 口地址的场景。 */
    @SerialName("system")
    SYSTEM,
}

/** Portal 认证与下线请求参数的编码方式。 */
@Serializable
public enum class PortalEncryption {
    /** 明文参数传输，登录密码只做标准 Base64 编码。 */
    @SerialName("plain")
    PLAIN,

    /** 使用客户端 IP 字符异或密钥对查询参数逐字符编码（附带 `encrypt=1` 标记），待编码字段不得超过 512 个字符。 */
    @SerialName("xor")
    XOR,
}

/**
 * Portal 认证或下线接口返回的 JSONP 结果。
 *
 * @property isSuccess 上线认证或下线注销是否成功（即 [result] 等于 `1L`）。
 * @property result 服务端结果码，`1L` 表示操作成功。
 * @property message 服务端返回的状态说明消息。
 * @property retCode 操作失败或特殊状态时附带的可选细分返回码。
 */
@Poko
@Serializable
public class PortalAuthResult(
    public val isSuccess: Boolean,
    public val result: Long,
    public val message: String,
    public val retCode: Long? = null,
)

/**
 * 校园网在线终端的会话唯一标识，可用于 [SelfServiceClient.kickDevice] 强制下线。
 *
 * @property value 会话 ID 字符串。
 */
@JvmInline
@Serializable
public value class SessionId(public val value: String)

/**
 * 校园网自助服务系统返回的当前在线终端信息。
 *
 * @property brasId 宽带远程接入服务器（BRAS）标识。
 * @property downFlow 已使用下行流量文本。
 * @property hostName 终端主机名，未上报时为空字符串。
 * @property ip 终端分配的内网 IP 地址文本。
 * @property loginTime 终端上线时刻；须用 [ZzuTimeZone] 换算为当地时间。
 * @property mac 终端 MAC 地址文本。
 * @property sessionId 在线会话唯一标识，可用于 [SelfServiceClient.kickDevice] 强制下线。
 * @property terminalType 终端设备类型描述。
 * @property upFlow 已使用上行流量文本。
 * @property onlineDuration 已在线时长。
 * @property userId 校园网账户数字用户 ID。
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

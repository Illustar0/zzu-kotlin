package com.illustar0.zzu.cas

import com.illustar0.zzu.internal.Base64ByteArraySerializer
import com.illustar0.zzu.internal.ZonedInstantSerializer
import dev.drewhamilton.poko.Poko
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 可通过 [CasClient.serviceSsoUrl] 获取单点登录跳转地址的业务系统。 */
@Poko
public class SsoService private constructor(internal val wire: JsonElement) {
    public companion object {
        /** 校园邮箱系统。 */
        public val EMAIL: SsoService = SsoService(JsonPrimitive("email"))

        /** 图书馆门户系统。 */
        public val LIBRARY: SsoService = SsoService(JsonPrimitive("library"))

        /** 图书馆空间（座位与研讨间）预约系统。 */
        public val LIBRARY_RESERVATION: SsoService = SsoService(JsonPrimitive("library_reservation"))

        /** 学生请假系统。 */
        public val LEAVE: SsoService = SsoService(JsonPrimitive("leave"))

        /** 校长信箱系统。 */
        public val PRESIDENT_MAILBOX: SsoService = SsoService(JsonPrimitive("president_mailbox"))

        /** 校园卡（一卡通）系统。 */
        public val ECARD: SsoService = SsoService(JsonPrimitive("ecard"))
    }

    /** 本科教务系统（EAMS）支持携带当前会话令牌直接跳转的前端子应用。 */
    public object UndergradEas {
        /** 学籍信息查询。 */
        public val STUDENT_STATUS: SsoService = createService("student_status")

        /** 培养方案完成情况。 */
        public val PROGRAM_COMPLETION: SsoService = createService("program_completion")

        /** 考试查询。 */
        public val EXAMS: SsoService = createService("exams")

        /** 学业预警查询。 */
        public val ACADEMIC_WARNINGS: SsoService = createService("academic_warnings")

        /** 成绩查询。 */
        public val GRADES: SsoService = createService("grades")

        /** 课表查询。 */
        public val TIMETABLE: SsoService = createService("timetable")

        /** 全校开课查询。 */
        public val COURSE_OFFERINGS: SsoService = createService("course_offerings")

        /** 校历。 */
        public val SCHOOL_CALENDAR: SsoService = createService("school_calendar")

        /** 空闲教室查询。 */
        public val AVAILABLE_CLASSROOMS: SsoService = createService("available_classrooms")

        /** 教室课表。 */
        public val CLASSROOM_TIMETABLE: SsoService = createService("classroom_timetable")

        private fun createService(app: String): SsoService =
            SsoService(buildJsonObject { put("undergrad_eas", app) })
    }
}

/**
 * 统一身份认证签发的用户令牌与刷新令牌对。
 *
 * 可持久化保存后经 [CasClient.setTokens] 恢复；切勿写入日志，[toString] 已隐藏令牌内容。
 *
 * @property userToken 访问各业务系统的用户 JWT（有效期约 1 个月）。
 * @property refreshToken 登录时一并签发的长效刷新 JWT（有效期约 2 个月）。
 * SDK 只在预置或复用令牌时校验它尚未过期，并原样保留供应用持久化，不会调用刷新接口自动续期。
 */
@Poko
@Serializable
public class Tokens(public val userToken: String, public val refreshToken: String) {
    override fun toString(): String = "Tokens(<redacted>)"
}

/**
 * 当前会话的多因素认证（MFA）检测与校验状态。
 *
 * @property isRequired 当前账号与设备登录是否需要完成多因素认证。
 * @property canUseSms 当前账号是否支持安全手机短信验证。
 * @property isVerified 当前多因素认证流程是否已校验通过。
 */
@Poko
@Serializable
public class MfaStatus(
    public val isRequired: Boolean,
    public val canUseSms: Boolean,
    public val isVerified: Boolean,
)

/**
 * 短信多因素认证的发送上下文。
 *
 * @property securePhone 服务端返回的脱敏安全手机号；服务端未提供时为 `null`。
 */
@Poko
@Serializable
public class SmsChallenge(
    public val securePhone: String? = null,
)

/**
 * 当前用户的个人身份基础信息。
 *
 * 包含学号、姓名等敏感字段，[toString] 只输出 [identityTypeName] 与 [organizationName]。
 *
 * @property uid 学号或工号（用户唯一标识）。
 * @property name 用户姓名。
 * @property identityTypeName 身份类别名称（如本科生、研究生、教职工）。
 * @property identityTypeId 身份类别编码。
 * @property organizationName 所属院系或组织机构名称。
 * @property organizationId 所属院系或组织机构编码。
 */
@Poko
@Serializable
public class PersonalInfo(
    public val uid: String,
    public val name: String,
    public val identityTypeName: String,
    public val identityTypeId: String,
    public val organizationName: String,
    public val organizationId: String,
) {
    override fun toString(): String =
        "PersonalInfo(identityTypeName=$identityTypeName, organizationName=$organizationName)"
}

/**
 * 当前账号下曾登录过的单条终端设备或网页会话与免多因素认证信任状态。
 *
 * 非空的 [deviceId] 属于认证凭据，[toString] 隐藏该字段及 [userAgent]。
 * 各非空登录时刻须用 [ZzuTimeZone][com.illustar0.zzu.ZzuTimeZone] 换算为当地时间。
 *
 * @property id 个人安全中心设备记录唯一标识。
 * @property deviceId 客户端设备标识，与创建客户端时的 `deviceId` 及 [CasClient.setDeviceTrust] 的入参对应；
 * 桌面或手机浏览器网页登录记录无此标识，为 `null`。
 * @property isTrusted 是否已设为受信任设备；受信任设备登录时无需多因素认证。
 * @property deviceType 终端设备形态类别，如 `"PC"`、`"MOBILE"`。
 * @property os 操作系统标识，如 `"MAC_OS_X"`、`"ANDROID"`。
 * @property browser 浏览器或客户端标识。
 * @property userAgent 客户端上报的完整 `User-Agent` 字符串。
 * @property usedCount 累计登录次数。
 * @property firstUsedTime 首次登录时刻，未记录时为 `null`。
 * @property lastUsedTime 最近一次登录时刻，未记录时为 `null`。
 * @property previousUsedTime 上一次登录时刻，未记录时为 `null`。
 */
@Poko
@Serializable
@Suppress("LongParameterList") // 构造参数与 libzzu 的登录设备记录 JSON 字段一一对应。
public class LoginDevice(
    public val id: String,
    public val deviceId: String? = null,
    public val isTrusted: Boolean,
    public val deviceType: String? = null,
    public val os: String? = null,
    public val browser: String? = null,
    public val userAgent: String? = null,
    public val usedCount: Int,
    @Serializable(with = ZonedInstantSerializer::class)
    public val firstUsedTime: Instant? = null,
    @Serializable(with = ZonedInstantSerializer::class)
    public val lastUsedTime: Instant? = null,
    @Serializable(with = ZonedInstantSerializer::class)
    public val previousUsedTime: Instant? = null,
) {
    override fun toString(): String =
        "LoginDevice(id=$id, isTrusted=$isTrusted, deviceType=$deviceType, " +
            "os=$os, usedCount=$usedCount, lastUsedTime=$lastUsedTime)"
}

/**
 * 历史登录设备单页查询结果。
 *
 * @property items 当前页返回的历史登录设备列表。
 * @property page 当前页码，从 1 开始。
 * @property pageSize 本次实际使用的每页条数。
 * @property totalItems 历史登录设备总条数。
 * @property totalPages 总页数。
 */
@Poko
@Serializable
public class DevicePage(
    public val items: List<LoginDevice>,
    public val page: Int,
    public val pageSize: Int,
    public val totalItems: Int,
    public val totalPages: Int,
) {
    /** 当前页之后是否还有更多分页。 */
    public val hasNext: Boolean
        get() = page < totalPages

    public companion object {
        /** 历史登录设备分页查询的默认每页条数。 */
        public const val DEFAULT_PAGE_SIZE: Int = 20
    }
}

/**
 * 当前用户在统一身份认证个人中心的头像图像数据。
 *
 * 为避免输出大体积二进制内容与泄露个人照片，[toString] 仅打印 [mediaType] 与字节长度。
 *
 * @property mediaType 小写的图像 MIME 媒体类型，如 `"image/png"`、`"image/jpeg"`。
 * @property bytes 原始图像二进制字节流。
 */
@Poko
@Serializable
public class Portrait(
    public val mediaType: String,
    @Poko.ReadArrayContent
    @SerialName("data")
    @Serializable(with = Base64ByteArraySerializer::class)
    public val bytes: ByteArray,
) {
    override fun toString(): String = "Portrait(mediaType=$mediaType, byteCount=${bytes.size})"
}

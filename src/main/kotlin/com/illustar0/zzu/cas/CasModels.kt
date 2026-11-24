package com.illustar0.zzu.cas

import dev.drewhamilton.poko.Poko
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

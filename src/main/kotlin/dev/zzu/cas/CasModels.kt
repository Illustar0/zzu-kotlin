package dev.zzu.cas

import dev.drewhamilton.poko.Poko
import dev.zzu.internal.DecimalSerializer
import java.math.BigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 可经 [CasClient.serviceLoginUrl] 单点登录的业务系统。 */
@Poko
public class SsoService private constructor(internal val wire: JsonElement) {
    public companion object {
        /** 校园邮箱。 */
        public val EMAIL: SsoService = SsoService(JsonPrimitive("email"))

        /** 图书馆门户。 */
        public val LIBRARY: SsoService = SsoService(JsonPrimitive("library"))

        /** 图书馆空间（座位与研讨间）预约。 */
        public val LIBRARY_RESERVATION: SsoService = SsoService(JsonPrimitive("library_reservation"))

        /** 学生请假系统。 */
        public val LEAVE: SsoService = SsoService(JsonPrimitive("leave"))

        /** 校长信箱。 */
        public val PRESIDENT_MAILBOX: SsoService = SsoService(JsonPrimitive("president_mailbox"))

        /** 校园卡（一卡通）。 */
        public val ECARD: SsoService = SsoService(JsonPrimitive("ecard"))
    }

    /** 携带当前会话令牌直接访问的本科教务子应用。 */
    public object UndergradEas {
        /** 学籍信息查询。 */
        public val STUDENT_STATUS: SsoService = createService("student_status")

        /** 培养方案完成情况。 */
        public val PROGRAM_COMPLETION: SsoService = createService("program_completion")

        /** 选课。 */
        public val COURSE_SELECTION: SsoService = createService("course_selection")

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
 * CAS 签发的令牌对，可持久化后经 [CasClient.setTokens] 恢复；[toString] 不输出令牌内容。
 *
 * @property userToken 访问各业务系统的用户令牌。
 * @property refreshToken 随登录签发的刷新令牌。
 */
@Poko
@Serializable
public class Tokens(public val userToken: String, public val refreshToken: String) {
    override fun toString(): String = "Tokens(<redacted>)"
}

/**
 * 多因素认证状态。
 *
 * @property isRequired 当前账号与设备是否必须完成多因素认证。
 * @property canUseSms 是否支持安全手机短信验证。
 * @property isVerified 当前流程是否已校验通过。
 */
@Poko
@Serializable
public class MfaStatus(
    public val isRequired: Boolean,
    public val canUseSms: Boolean,
    public val isVerified: Boolean,
)

/**
 * 个人信息；[toString] 不输出姓名、学号与余额。
 *
 * @property uid 学号或工号。
 * @property balance 校园卡余额，单位为元。
 */
@Poko
@Serializable
@Suppress("LongParameterList") // 构造参数与 libzzu 的个人信息 JSON 字段一一对应。
public class PersonalInfo(
    public val uid: String,
    public val name: String,
    public val identityTypeName: String,
    public val identityTypeId: String,
    public val organizationName: String,
    public val organizationId: String,
    public val unreadEmailCount: Long,
    @Serializable(with = DecimalSerializer::class)
    public val balance: BigDecimal,
    public val researchCount: Long,
) {
    override fun toString(): String =
        "PersonalInfo(identityTypeName=$identityTypeName, organizationName=$organizationName)"
}

package dev.zzu.cas

import dev.drewhamilton.poko.Poko
import dev.zzu.internal.DecimalSerializer
import java.math.BigDecimal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 可经 [CasClient.serviceLoginUrl] 单点登录的业务系统。 */
@Serializable
public enum class SsoService {
    /** 校园邮箱。 */
    @SerialName("email")
    EMAIL,

    /** 图书馆门户。 */
    @SerialName("library")
    LIBRARY,

    /** 图书馆空间（座位与研讨间）预约。 */
    @SerialName("library_reservation")
    LIBRARY_RESERVATION,

    /** 学生请假系统。 */
    @SerialName("leave")
    LEAVE,
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

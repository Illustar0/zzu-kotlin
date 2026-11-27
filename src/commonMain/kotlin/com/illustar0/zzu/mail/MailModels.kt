package com.illustar0.zzu.mail

import com.illustar0.zzu.ZzuTimeZone
import com.illustar0.zzu.internal.ZonedInstantSerializer
import dev.drewhamilton.poko.Poko
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * 收件箱单封邮件摘要元数据。
 *
 * 仅承载列表预览所需的发件人、主题、纯文本摘要、时间与状态标记，不含富文本正文与附件内容；
 * 阅读完整邮件或收发附件请通过 [MailClient.webmailUrl] 跳转网页版完成。
 * [sentTime] 与 [receivedTime] 须用 [ZzuTimeZone] 换算为当地时间。
 *
 * @property id Coremail 邮件唯一标识。
 * @property from 发件人显示文本。
 * @property to 收件人显示文本。
 * @property subject 邮件主题，无主题邮件为空字符串。
 * @property summary 纯文本正文摘要预览，无摘要时为 `null`。
 * @property sentTime 发信头声明的发送时刻，缺失或格式无法识别时为 `null`。
 * @property receivedTime 邮件到达服务器的时刻。
 * @property size 邮件原始大小，单位为字节。
 * @property isRead 是否已读。
 * @property hasAttachment 是否带有附件。
 * @property isFlagged 是否已加星标（红旗标记）。
 */
@Poko
@Serializable
@Suppress("LongParameterList") // 构造参数与 libzzu 的邮件摘要 JSON 字段一一对应。
public class MailMessage(
    public val id: String,
    public val from: String,
    public val to: String,
    public val subject: String,
    public val summary: String? = null,
    @Serializable(with = ZonedInstantSerializer::class)
    public val sentTime: Instant? = null,
    @Serializable(with = ZonedInstantSerializer::class)
    public val receivedTime: Instant,
    public val size: Long,
    public val isRead: Boolean,
    public val hasAttachment: Boolean,
    public val isFlagged: Boolean,
)

/**
 * 收件箱邮件摘要单页查询结果。
 *
 * @property items 当前页返回的邮件摘要列表，按接收时间倒序排列。
 * @property page 当前页码，从 1 开始。
 * @property pageSize 本次实际使用的每页条数。
 * @property totalItems 收件箱中的邮件总封数。
 * @property totalPages 总页数。
 */
@Poko
@Serializable
public class MailMessagePage(
    public val items: List<MailMessage>,
    public val page: Int,
    public val pageSize: Int,
    public val totalItems: Int,
    public val totalPages: Int,
) {
    /** 当前页之后是否还有更多分页。 */
    public val hasNext: Boolean
        get() = page < totalPages

    public companion object {
        /** 收件箱邮件摘要分页查询的默认每页条数。 */
        public const val DEFAULT_PAGE_SIZE: Int = 20
    }
}

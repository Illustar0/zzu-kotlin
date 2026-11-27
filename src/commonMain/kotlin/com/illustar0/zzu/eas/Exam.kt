package com.illustar0.zzu.eas

import com.illustar0.zzu.ZzuTimeZone
import com.illustar0.zzu.internal.IsoDayOfWeekSerializer
import com.illustar0.zzu.internal.ZonedInstantSerializer
import dev.drewhamilton.poko.Poko
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * 单场学生待考安排记录。
 *
 * 教务移动端接口仅返回当前尚未结束的考试安排；尚未排定时间或考场时，[date]、[startTime]、[endTime] 与 [place] 可能为 `null`。
 * 排定起止时间后，[startTime] 与 [endTime] 成对出现且满足 `startTime < endTime`，须用 [ZzuTimeZone] 换算为当地时间。
 *
 * @property id 考试安排记录 ID。
 * @property courseCode 课程代码。
 * @property courseNameZh 课程中文名称。
 * @property courseNameEn 课程英文名称。
 * @property classCode 教学班选课课号。
 * @property examType 考试类别，如期末考试、期中考试、补考、缓考。
 * @property cultivateType 培养类别，如主修、辅修。
 * @property date 考试公历日期，尚未排定时为 `null`。
 * @property weekday 考试星期，[date] 非空时恒有值并与之匹配。
 * @property startTime 考试开始时刻，尚未排定时为 `null`。
 * @property endTime 考试结束时刻，与 [startTime] 成对出现且严格晚于 [startTime]。
 * @property place 考场地点中文描述，尚未排定考场时为 `null`。
 * @property placeEn 考场地点英文描述。
 * @property seatNumber 考场座位号文本。
 */
@Poko
@Serializable
@Suppress("LongParameterList") // 构造参数与 libzzu 的考试安排 JSON 字段一一对应。
public class Exam(
    public val id: Long? = null,
    public val courseCode: String? = null,
    public val courseNameZh: String,
    public val courseNameEn: String? = null,
    public val classCode: String? = null,
    public val examType: NamedEntity? = null,
    public val cultivateType: NamedEntity? = null,
    public val date: LocalDate? = null,
    @Serializable(with = IsoDayOfWeekSerializer::class)
    public val weekday: DayOfWeek? = null,
    @Serializable(with = ZonedInstantSerializer::class)
    public val startTime: Instant? = null,
    @Serializable(with = ZonedInstantSerializer::class)
    public val endTime: Instant? = null,
    public val place: String? = null,
    public val placeEn: String? = null,
    public val seatNumber: String? = null,
) {
    /** 该场考试是否已排定具体的起止时间。 */
    public val isScheduled: Boolean
        get() = startTime != null && endTime != null
}

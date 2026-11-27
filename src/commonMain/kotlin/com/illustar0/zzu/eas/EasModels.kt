package com.illustar0.zzu.eas

import dev.drewhamilton.poko.Poko
import kotlin.jvm.JvmInline
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val DAYS_PER_WEEK: Int = 7

/**
 * 带中英文名称与编码的通用教务命名实体，如校区、教学楼、课程性质与开课院系。
 *
 * @property id 实体数字 ID。
 * @property nameZh 中文名称。
 * @property nameEn 英文名称。
 * @property code 教务编码。
 */
@Poko
@Serializable
public class NamedEntity(
    public val id: Long,
    public val nameZh: String = "",
    public val nameEn: String? = null,
    public val code: String = "",
)

/**
 * 一天内的作息时段归属。
 *
 * 已知取值以 `snake_case` 常量暴露；遇到教务上游新增的其他字符串时会原样保留在 [value] 中。
 *
 * @property value 规范化后的时段标识文本。
 */
@JvmInline
@Serializable
public value class DayPart(public val value: String) {
    public companion object {
        /** 上午。 */
        public val MORNING: DayPart = DayPart("morning")

        /** 中午，如午间加节。 */
        public val NOON: DayPart = DayPart("noon")

        /** 下午。 */
        public val AFTERNOON: DayPart = DayPart("afternoon")

        /** 晚上。 */
        public val EVENING: DayPart = DayPart("evening")
    }
}

/**
 * 每日作息表中的单节时间与时段定义。
 *
 * 不绑定具体公历日期，既用于渲染课表纵轴，也用于将教室占用区间映射到标准节次网格。
 *
 * @property index 节次序号，从 1 开始，对应 [ClassSession.startUnit] 与 [ClassSession.endUnit]。
 * @property nameZh 节次中文名称，常规作息为 `"1"` 至 `"10"`，含加节作息为 `"第一节"`、`"第+1节"` 等。
 * @property nameEn 节次英文名称。
 * @property dayPart 所属时段。
 * @property segmentIndex 连排大节分组序号，从 1 开始；同一大节内的相邻小节共享相同序号。
 * @property startTime 本节上课时刻。
 * @property endTime 本节下课时刻，严格晚于 [startTime]。
 */
@Poko
@Serializable
@Suppress("LongParameterList") // 构造参数与 libzzu 的节次作息 JSON 字段一一对应。
public class CourseUnit(
    public val index: Int,
    public val nameZh: String,
    public val nameEn: String? = null,
    public val dayPart: DayPart,
    public val segmentIndex: Int,
    public val startTime: LocalTime,
    public val endTime: LocalTime,
)

/**
 * 教务学期数字 ID。
 *
 * @property value 底层教务系统的学期数字标识。
 */
@JvmInline
@Serializable
public value class SemesterId(public val value: Long)

/**
 * 学期季节标识。
 *
 * 已知取值以 `snake_case` 常量暴露；遇到教务上游新增的其他字符串时会原样保留在 [value] 中。
 *
 * @property value 规范化后的季节标识文本。
 */
@JvmInline
@Serializable
public value class SemesterSeason(public val value: String) {
    public companion object {
        /** 春季学期。 */
        public val SPRING: SemesterSeason = SemesterSeason("spring")

        /** 秋季学期。 */
        public val AUTUMN: SemesterSeason = SemesterSeason("autumn")

        /** 夏季学期。 */
        public val SUMMER: SemesterSeason = SemesterSeason("summer")

        /** 冬季学期。 */
        public val WINTER: SemesterSeason = SemesterSeason("winter")
    }
}

/**
 * 教务学期校历元数据。
 *
 * 支持在本地离线换算公历日期与教学周序号，无需发起网络请求。
 *
 * @property id 学期 ID。
 * @property code 学期代码，如 `"2026-2027-1"`。
 * @property nameZh 学期中文名称。
 * @property nameEn 学期英文名称。
 * @property schoolYear 所属学年，如 `"2026-2027"`。
 * @property season 学期季节。
 * @property startDate 学期起始公历日期（含）。
 * @property endDate 学期结束公历日期（含）。
 * @property startsWeekOnSunday 教学周是否以周日作为每周第一天。
 * @property isCountedInTerm 是否计入常规学期统计。
 * @property weekIndices 本学期包含的教学周序号列表，按校历顺序排列，非零且唯一。
 */
@Poko
@Serializable
@Suppress("LongParameterList") // 构造参数与 libzzu 的学期校历 JSON 字段一一对应。
public class Semester(
    public val id: SemesterId,
    public val code: String,
    public val nameZh: String,
    public val nameEn: String? = null,
    public val schoolYear: String,
    public val season: SemesterSeason,
    public val startDate: LocalDate,
    public val endDate: LocalDate,
    @SerialName("weekStartOnSunday")
    public val startsWeekOnSunday: Boolean,
    @SerialName("countInTerm")
    public val isCountedInTerm: Boolean,
    public val weekIndices: List<Int>,
) {
    /**
     * 计算公历日期 [date] 在本学期中对应的教学周序号。
     *
     * 当 [date] 位于本学期起止日期范围之外，或超出 [weekIndices] 覆盖范围时返回 `null`。
     */
    public fun weekOf(date: LocalDate): Int? {
        if (date < startDate || date > endDate) return null
        val days = startDate.weekStart(startsWeekOnSunday)
            .daysUntil(date.weekStart(startsWeekOnSunday))
        return days.takeIf { it >= 0 }?.let { weekIndices.getOrNull(it / DAYS_PER_WEEK) }
    }

    /**
     * 返回教学周 [weekIndex] 对应的完整 7 天公历日期闭区间；本学期不含该周次时返回 `null`。
     *
     * 周首日依据 [startsWeekOnSunday] 确定（周日或周一）。
     */
    public fun weekDateRange(weekIndex: Int): ClosedRange<LocalDate>? {
        val position = weekIndices.indexOf(weekIndex)
        if (position < 0) return null
        val first = startDate.weekStart(startsWeekOnSunday)
            .plus(position * DAYS_PER_WEEK, DateTimeUnit.DAY)
        return first..first.plus(DAYS_PER_WEEK - 1, DateTimeUnit.DAY)
    }
}

/**
 * 在学期集合中查找公历日期 [date] 所属的学期与教学周序号。
 *
 * 多个学期包含同一日期时，优先匹配 [Semester.isCountedInTerm] 为 `true` 的常规学期；
 * 假期等非学期日期返回 `null`。
 */
public fun Iterable<Semester>.locateDate(date: LocalDate): Pair<Semester, Int>? =
    firstNotNullOfOrNull { semester ->
        if (semester.isCountedInTerm) semester.weekOf(date)?.let { semester to it } else null
    } ?: firstNotNullOfOrNull { semester ->
        if (!semester.isCountedInTerm) semester.weekOf(date)?.let { semester to it } else null
    }

private fun LocalDate.weekStart(startsOnSunday: Boolean): LocalDate {
    val offset = if (startsOnSunday) {
        dayOfWeek.isoDayNumber % DAYS_PER_WEEK
    } else {
        dayOfWeek.isoDayNumber - 1
    }
    return minus(offset, DateTimeUnit.DAY)
}

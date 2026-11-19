package dev.zzu.eas

import dev.drewhamilton.poko.Poko
import dev.zzu.ZzuTimeZone
import dev.zzu.internal.IsoDayOfWeekSerializer
import dev.zzu.internal.ZonedInstantSerializer
import dev.zzu.internal.ZzuJson
import dev.zzu.internal.nativeTimetableToCalendar
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 教务学期数字 ID。
 *
 * @property value 底层教务系统的学期数字标识。
 */
@JvmInline
@Serializable
public value class SemesterId(public val value: Long)

/**
 * 教务学期校历元数据。
 *
 * @property id 学期 ID。
 * @property code 学期代码。
 * @property nameZh 学期中文名称。
 * @property nameEn 学期英文名称。
 * @property schoolYear 所属学年标识，如 `2026-2027`。
 * @property startDate 学期起始公历日期。
 * @property endDate 学期结束公历日期。
 * @property startsWeekOnSunday 教学周是否以周日作为每周第一天。
 * @property isCountedInTerm 是否计入常规学期统计。
 * @property season 学期季节代码或名称。
 * @property weekIndices 本学期包含的教学周序号列表，按校历顺序排列。
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
    public val startDate: LocalDate,
    public val endDate: LocalDate,
    @SerialName("weekStartOnSunday")
    public val startsWeekOnSunday: Boolean,
    @SerialName("countInTerm")
    public val isCountedInTerm: Boolean,
    public val season: String,
    public val weekIndices: List<Int>,
)

/**
 * 单个教学周内的排课集合。
 *
 * @property weekIndex 教学周序号，从 1 开始。
 * @property lessons 本周内的所有排课记录。
 */
@Poko
@Serializable
public class TeachingWeek(
    public val weekIndex: Int,
    public val lessons: List<Lesson>,
) {
    /**
     * 查询 [day] 第 [unit] 节上的排课；若存在重叠排课则返回最后一条记录，没有排课时返回 `null`。
     *
     * @throws IllegalArgumentException [unit] 不在 [UNITS] 范围内时。
     */
    public operator fun get(day: DayOfWeek, unit: Int): Lesson? {
        require(unit in UNITS) { "节次范围为 $UNITS，实际为 $unit" }
        return lessons.lastOrNull { lesson ->
            val schedule = lesson.schedule
            schedule.weekday == day && unit in schedule.startUnit..schedule.endUnit
        }
    }

    public companion object {
        /** 每天包含的标准节次范围（第 1 至第 10 节）。 */
        public val UNITS: IntRange = 1..10
    }
}

/** 按第 1 至第 10 节的顺序返回 [day] 全天的排课槽位，长度等于 [TeachingWeek.UNITS] 的节数。 */
public fun TeachingWeek.day(day: DayOfWeek): List<Lesson?> =
    TeachingWeek.UNITS.map { unit -> this[day, unit] }

/**
 * 按周一至周日的顺序返回第 [unit] 节在一周内的排课槽位。
 *
 * @throws IllegalArgumentException [unit] 不在 [TeachingWeek.UNITS] 范围内时。
 */
public fun TeachingWeek.unit(unit: Int): List<Lesson?> =
    DayOfWeek.entries.map { day -> this[day, unit] }

/** 生成 `7 × 10`（外层按周一至周日、内层按第 1 至第 10 节）的整周二维课表网格。 */
public fun TeachingWeek.grid(): List<List<Lesson?>> = DayOfWeek.entries.map(::day)

/**
 * 将多周课表去重后导出为 `iCalendar`（`.ics`）文本，只做本地计算，无需客户端或登录。
 *
 * 按事件 UID 去重，并在当前线程同步执行；耗时随课表长度增长，整学期课表宜放到后台调度器上执行。
 *
 * @throws dev.zzu.ZzuException 课表数据不被 `libzzu` 接受时。
 */
public fun List<TeachingWeek>.toICalendar(): String =
    nativeTimetableToCalendar(ZzuJson.encodeToString(this))

/** 将单周课表去重后导出为 `iCalendar`（`.ics`）文本，行为与异常同 [List.toICalendar]。 */
public fun TeachingWeek.toICalendar(): String = listOf(this).toICalendar()

/**
 * 课表项，将课程元数据与单次排课绑定在一起。
 *
 * @property course 课程基础元数据。
 * @property schedule 本次排课的时间、地点与授课教师详情。
 */
@Poko
@Serializable
public class Lesson(
    public val course: Course,
    public val schedule: Schedule,
)

/**
 * 教务课程基础元数据。
 *
 * @property id 课程数字 ID。
 * @property code 课程代码。
 * @property nameZh 课程中文名称。
 * @property nameEn 课程英文名称。
 * @property credits 课程学分，实验课为 `null`。
 * @property periodInfo 学时与周次统计信息。
 */
@Poko
@Serializable
public class Course(
    public val id: Long,
    public val code: String,
    public val nameZh: String,
    public val nameEn: String? = null,
    public val credits: Double? = null,
    public val periodInfo: PeriodInfo,
)

/**
 * 课程学时与周次统计信息。
 *
 * @property total 课程总学时。
 * @property weeks 开课总周数，实验课为 `null`。
 * @property periodsPerWeek 每周学时数，实验课为 `null`。
 */
@Poko
@Serializable
public class PeriodInfo(
    public val total: Int,
    public val weeks: Int? = null,
    public val periodsPerWeek: Int? = null,
)

/**
 * 单次排课的时间、地点与授课教师详情。
 *
 * 解码时由 `libzzu` 校验星期、节次（`1..10` 且起始节次小于等于结束节次）、教学周（非零）及起止时间顺序；
 * 当上游以 `0` 表示未单独指定实际时间（常见于实验课）时，[realStartTime] 与 [realEndTime] 会回退为 [startTime] 与 [endTime]。
 * 时刻须用 [ZzuTimeZone] 换算为当地时间：
 *
 * ```kotlin
 * val startsAt = schedule.realStartTime.toLocalDateTime(ZzuTimeZone).time
 * ```
 *
 * @property scheduleGroupId 排课组 ID。
 * @property date 上课公历日期。
 * @property weekday 上课星期。
 * @property startTime 课表标准开始时刻。
 * @property endTime 课表标准结束时刻。
 * @property realStartTime 实际开始时刻，未单独指定时与 [startTime] 相同。
 * @property realEndTime 实际结束时刻，未单独指定时与 [endTime] 相同。
 * @property teacherName 授课教师中文姓名。
 * @property teacherNameEn 授课教师英文姓名。
 * @property customPlace 自定义上课地点描述（无固定教室时使用）。
 * @property room 固定上课教室，未分配时为 `null`。
 * @property startUnit 起始节次，取值在 [TeachingWeek.UNITS] 内。
 * @property endUnit 结束节次，取值在 [TeachingWeek.UNITS] 内且大于等于 [startUnit]。
 * @property state 排课状态文本（如 `"已结束"`），实验课为 `null`。
 * @property weekIndex 所属教学周序号，从 1 开始。
 * @property lessonType 授课类型标识（如 `"THEORY"`），实验课为 `null`。
 * @property periods 本次排课占用的学时数，实验课为 `null`。
 */
@Poko
@Serializable
@Suppress("LongParameterList") // 构造参数与 libzzu 的排课 JSON 字段一一对应。
public class Schedule(
    public val scheduleGroupId: Long,
    public val date: LocalDate,
    @Serializable(with = IsoDayOfWeekSerializer::class)
    public val weekday: DayOfWeek,
    @Serializable(with = ZonedInstantSerializer::class)
    public val startTime: Instant,
    @Serializable(with = ZonedInstantSerializer::class)
    public val endTime: Instant,
    @Serializable(with = ZonedInstantSerializer::class)
    public val realStartTime: Instant,
    @Serializable(with = ZonedInstantSerializer::class)
    public val realEndTime: Instant,
    public val teacherName: String,
    public val teacherNameEn: String? = null,
    public val customPlace: String? = null,
    public val room: Room? = null,
    public val startUnit: Int,
    public val endUnit: Int,
    public val state: String? = null,
    public val weekIndex: Int,
    public val lessonType: String? = null,
    public val periods: Int? = null,
)

/**
 * 上课教室及其所属教学楼与校区信息。
 *
 * @property id 教室数字 ID。
 * @property nameZh 教室中文名称。
 * @property nameEn 教室英文名称。
 * @property building 所属教学楼。
 * @property campus 所属校区。
 */
@Poko
@Serializable
public class Room(
    public val id: Long,
    public val nameZh: String,
    public val nameEn: String? = null,
    public val building: NamedEntity,
    public val campus: NamedEntity,
)

/**
 * 带中英文名称与编码的通用教务实体，如校区、教学楼。
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
    public val nameZh: String,
    public val nameEn: String? = null,
    public val code: String,
)

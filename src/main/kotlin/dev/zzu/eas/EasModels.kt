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

/** 学期 ID。 */
@JvmInline
@Serializable
public value class SemesterId(public val value: Long)

/**
 * 学期校历。
 *
 * @property schoolYear 所属学年，如 `2026-2027`。
 * @property startsWeekOnSunday 教学周是否以周日为第一天。
 * @property isCountedInTerm 是否计入常规学期统计。
 * @property weekIndices 本学期的教学周序号，按校历排列。
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
 * 单个教学周的排课。
 *
 * @property weekIndex 教学周序号，从 1 开始。
 */
@Poko
@Serializable
public class TeachingWeek(
    public val weekIndex: Int,
    public val lessons: List<Lesson>,
) {
    /**
     * 返回 [day] 第 [unit] 节的排课；排课重叠时取最后一条，没有排课时为 `null`。
     *
     * @throws IllegalArgumentException [unit] 不在 [UNITS] 内时。
     */
    public operator fun get(day: DayOfWeek, unit: Int): Lesson? {
        require(unit in UNITS) { "节次范围为 $UNITS，实际为 $unit" }
        return lessons.lastOrNull { lesson ->
            val schedule = lesson.schedule
            schedule.weekday == day && unit in schedule.startUnit..schedule.endUnit
        }
    }

    public companion object {
        /** 每天的标准节次。 */
        public val UNITS: IntRange = 1..10
    }
}

/** 返回 [day] 全天按节次排列的槽位，长度为 [TeachingWeek.UNITS] 的节数。 */
public fun TeachingWeek.day(day: DayOfWeek): List<Lesson?> =
    TeachingWeek.UNITS.map { unit -> this[day, unit] }

/** 返回第 [unit] 节周一至周日的槽位。 */
public fun TeachingWeek.unit(unit: Int): List<Lesson?> =
    DayOfWeek.entries.map { day -> this[day, unit] }

/** 返回 7 × 10 的整周课表，外层为周一至周日，内层为第 1 至第 10 节。 */
public fun TeachingWeek.grid(): List<List<Lesson?>> = DayOfWeek.entries.map(::day)

/**
 * 把多周课表渲染为 iCalendar（`.ics`）文本，只做本地计算，无需客户端或登录。
 *
 * 在当前线程同步执行，耗时随课表长度增长，整学期课表宜放到后台线程。
 *
 * @throws dev.zzu.ZzuException 课表数据不被 libzzu 接受时。
 */
public fun List<TeachingWeek>.toICalendar(): String =
    nativeTimetableToCalendar(ZzuJson.encodeToString(this))

/** 把单周课表渲染为 iCalendar（`.ics`）文本，规则同 [List.toICalendar]。 */
public fun TeachingWeek.toICalendar(): String = listOf(this).toICalendar()

/** 绑定课程元数据与单次排课的课表项。 */
@Poko
@Serializable
public class Lesson(
    public val course: Course,
    public val schedule: Schedule,
)

/**
 * 课程元数据。
 *
 * @property credits 学分，实验课为 `null`。
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
 * 课程学时统计。
 *
 * @property total 总学时。
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
 * 单次排课。时刻用 [ZzuTimeZone] 换算成当地时间：
 *
 * ```kotlin
 * val startsAt = schedule.realStartTime.toLocalDateTime(ZzuTimeZone).time
 * ```
 *
 * @property realStartTime 实际开始时刻，未单独指定时与 [startTime] 相同。
 * @property realEndTime 实际结束时刻，未单独指定时与 [endTime] 相同。
 * @property customPlace 无固定教室时的上课地点。
 * @property room 固定教室，未分配时为 `null`。
 * @property startUnit 起始节次，取值在 [TeachingWeek.UNITS] 内。
 * @property endUnit 结束节次，不小于 [startUnit]。
 * @property state 排课状态文本，实验课为 `null`。
 * @property weekIndex 所属教学周序号。
 * @property lessonType 授课类型，如 `THEORY`，实验课为 `null`。
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

/** 上课教室及其所属教学楼与校区。 */
@Poko
@Serializable
public class Room(
    public val id: Long,
    public val nameZh: String,
    public val nameEn: String? = null,
    public val building: NamedEntity,
    public val campus: NamedEntity,
)

/** 带中英文名称与编码的教务实体，如校区、教学楼。 */
@Poko
@Serializable
public class NamedEntity(
    public val id: Long,
    public val nameZh: String,
    public val nameEn: String? = null,
    public val code: String,
)

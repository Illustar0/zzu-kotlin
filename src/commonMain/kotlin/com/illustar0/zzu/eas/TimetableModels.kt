package com.illustar0.zzu.eas

import com.illustar0.zzu.ZzuTimeZone
import com.illustar0.zzu.internal.IsoDayOfWeekSerializer
import com.illustar0.zzu.internal.ZonedInstantSerializer
import com.illustar0.zzu.internal.nativeTimetableToCalendar
import com.illustar0.zzu.internal.putValue
import dev.drewhamilton.poko.Poko
import kotlin.jvm.JvmInline
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject

/**
 * 课程学时与周次统计信息。
 *
 * @property total 课程总学时。
 * @property weeks 开课总周数。
 * @property periodsPerWeek 每周学时数。
 * @property theory 理论学时。
 * @property experiment 实验学时。
 * @property practice 实践学时。
 * @property design 设计学时。
 * @property machine 上机学时。
 * @property test 测验学时。
 */
@Poko
@Serializable
@Suppress("LongParameterList") // 构造参数与 libzzu 的学时统计 JSON 字段一一对应。
public class PeriodInfo(
    public val total: Int,
    public val weeks: Int? = null,
    public val periodsPerWeek: Int? = null,
    public val theory: Int? = null,
    public val experiment: Int? = null,
    public val practice: Int? = null,
    public val design: Int? = null,
    public val machine: Int? = null,
    public val test: Int? = null,
)

/**
 * 课程目录级基础元数据。
 *
 * @property id 课程数字 ID。
 * @property code 课程代码。
 * @property nameZh 课程中文名称。
 * @property nameEn 课程英文名称。
 * @property credits 课程学分，独立实验课程可能为 `null`。
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
 * 教学班任课教师姓名。
 *
 * @property nameZh 教师中文姓名。
 * @property nameEn 教师英文姓名。
 */
@Poko
@Serializable
public class TeacherName(
    public val nameZh: String,
    public val nameEn: String? = null,
)

/** 教学班数据来源。 */
@Serializable
public enum class ClassOrigin {
    /** 教务常规选课教学班。 */
    @SerialName("regular")
    REGULAR,

    /** 未匹配到常规教学班时，由独立实验排课归并生成的教学班。 */
    @SerialName("standalone_experiment")
    STANDALONE_EXPERIMENT,
}

/**
 * 教学班排课发布状态。
 *
 * 已知取值以 `snake_case` 常量暴露；遇到教务上游新增的其他字符串时会原样保留在 [value] 中。
 *
 * @property value 规范化后的状态标识文本。
 */
@JvmInline
@Serializable
public value class ScheduleState(public val value: String) {
    public companion object {
        /** 已发布。 */
        public val PUBLISHED: ScheduleState = ScheduleState("published")

        /** 未发布。 */
        public val UNPUBLISHED: ScheduleState = ScheduleState("unpublished")
    }
}

/**
 * 学期内选修的一个教学班，包含未排课课程与实验课程父班。
 *
 * @property id 教学班数字 ID，在 [Timetable.classes] 中唯一。
 * @property code 教学班选课课号，如 `"222302-013"`。
 * @property origin 教学班来源。
 * @property course 关联的课程元数据。
 * @property courseType 课程性质，如专业必修课、分类通识课。
 * @property cultivateType 培养类型，如主修、辅修。
 * @property department 开课院系或教学单位。
 * @property campus 开课校区。
 * @property teachers 教学班全部任课教师列表。
 * @property studentCount 选课学生人数。
 * @property scheduleState 教务排课发布状态。
 * @property scheduleText 官方汇总排课中文摘要。
 * @property scheduleTextEn 官方汇总排课英文摘要。
 * @property remark 教学班备注信息。
 */
@Poko
@Serializable
@Suppress("LongParameterList") // 构造参数与 libzzu 的教学班 JSON 字段一一对应。
public class TeachingClass(
    public val id: Long,
    public val code: String,
    public val origin: ClassOrigin,
    public val course: Course,
    public val courseType: NamedEntity? = null,
    public val cultivateType: NamedEntity? = null,
    public val department: NamedEntity? = null,
    public val campus: NamedEntity? = null,
    public val teachers: List<TeacherName> = emptyList(),
    public val studentCount: Int? = null,
    public val scheduleState: ScheduleState,
    public val scheduleText: String? = null,
    public val scheduleTextEn: String? = null,
    public val remark: String? = null,
)

/**
 * 单次排课的授课类型。
 *
 * 已知取值以 `snake_case` 常量暴露；遇到教务上游新增的其他字符串时会原样保留在 [value] 中。
 *
 * @property value 规范化后的授课类型标识文本。
 */
@JvmInline
@Serializable
public value class SessionKind(public val value: String) {
    /** 用于界面展示的中文授课类型名称；未知取值直接返回 [value]。 */
    public val labelZh: String
        get() = when (this) {
            THEORY -> "理论"
            EXPERIMENT -> "实验"
            PRACTICE -> "实践"
            MACHINE -> "上机"
            DESIGN -> "设计"
            else -> value
        }

    public companion object {
        /** 理论课。 */
        public val THEORY: SessionKind = SessionKind("theory")

        /** 实验课。 */
        public val EXPERIMENT: SessionKind = SessionKind("experiment")

        /** 实践课。 */
        public val PRACTICE: SessionKind = SessionKind("practice")

        /** 上机课。 */
        public val MACHINE: SessionKind = SessionKind("machine")

        /** 设计课。 */
        public val DESIGN: SessionKind = SessionKind("design")
    }
}

/**
 * 单次排课的执行状态。
 *
 * 已知取值以 `snake_case` 常量暴露；遇到教务上游新增的其他字符串时会原样保留在 [value] 中。
 *
 * @property value 规范化后的执行状态标识文本。
 */
@JvmInline
@Serializable
public value class SessionState(public val value: String) {
    public companion object {
        /** 未完成。 */
        public val PENDING: SessionState = SessionState("pending")

        /** 已完成或已结束。 */
        public val COMPLETED: SessionState = SessionState("completed")
    }
}

/**
 * 单次排课场次的授课教师信息。
 *
 * @property id 教务教师 ID，上游未提供时为 `null`。
 * @property personId 人员 ID，上游未提供时为 `null`。
 * @property nameZh 教师中文姓名。
 * @property nameEn 教师英文姓名。
 */
@Poko
@Serializable
public class SessionTeacher(
    public val id: Long? = null,
    public val personId: Long? = null,
    public val nameZh: String,
    public val nameEn: String? = null,
)

/**
 * 实验排课关联的具体实验项目详情。
 *
 * @property id 实验项目 ID。
 * @property code 实验项目编码。
 * @property nameZh 实验项目中文名称。
 * @property totalPeriods 该实验项目的总学时。
 */
@Poko
@Serializable
public class ExperimentProject(
    public val id: Long,
    public val code: String,
    public val nameZh: String,
    public val totalPeriods: Int? = null,
)

/**
 * 上课教室及其所属教学楼与校区信息。
 *
 * @property id 教室数字 ID。
 * @property nameZh 教室中文名称。
 * @property nameEn 教室英文名称。
 * @property building 所属教学楼。
 * @property campus 所属校区。
 * @property seatNumber 座位号说明，常规排课通常为 `null`。
 */
@Poko
@Serializable
public class Room(
    public val id: Long,
    public val nameZh: String,
    public val nameEn: String? = null,
    public val building: NamedEntity,
    public val campus: NamedEntity,
    public val seatNumber: String? = null,
)

/**
 * 单次具体上课场次。
 *
 * 同教学班在同一时间、同一地点由多位教师合上的上游重复记录会无损合并为单条场次，
 * 所有教师按顺序保留在 [teachers] 中。[startTime] 与 [endTime] 须用 [ZzuTimeZone] 换算为当地时间：
 *
 * ```kotlin
 * val startsAt = session.startTime.toLocalDateTime(ZzuTimeZone).time
 * ```
 *
 * @property classId 所属教学班 ID，对应 [Timetable.classes] 中的某一项。
 * @property scheduleGroupId 排课组 ID。
 * @property weekIndex 所属教学周序号，从 1 开始。
 * @property date 上课公历日期。
 * @property originalDate 调课前的原始上课日期，未调课时为 `null`。
 * @property weekday 上课星期。
 * @property startTime 有效开始时刻；上游提供非零实际时间时优先取实际时间。
 * @property endTime 有效结束时刻，严格晚于 [startTime]。
 * @property startUnit 起始节次，从 1 开始，不限 10 节。
 * @property endUnit 结束节次，不小于 [startUnit]。
 * @property startUnitNameZh 起始节次展示名称，如 `"1"`、`"第+1节"`。
 * @property endUnitNameZh 结束节次展示名称。
 * @property teachers 本场次授课教师列表，多人合上时包含全部教师。
 * @property room 固定上课教室，无固定教室时为 `null`。
 * @property customPlace 自定义上课地点描述，如实验室地址。
 * @property kind 授课类型。
 * @property state 执行状态，实验课通常为 `null`。
 * @property periods 本场次占用的学时数。
 * @property experimentProject 实验项目详情，非实验项目排课时为 `null`。
 */
@Poko
@Serializable
@Suppress("LongParameterList") // 构造参数与 libzzu 的排课场次 JSON 字段一一对应。
public class ClassSession(
    public val classId: Long,
    public val scheduleGroupId: Long,
    public val weekIndex: Int,
    public val date: LocalDate,
    public val originalDate: LocalDate? = null,
    @Serializable(with = IsoDayOfWeekSerializer::class)
    public val weekday: DayOfWeek,
    @Serializable(with = ZonedInstantSerializer::class)
    public val startTime: Instant,
    @Serializable(with = ZonedInstantSerializer::class)
    public val endTime: Instant,
    public val startUnit: Int,
    public val endUnit: Int,
    public val startUnitNameZh: String? = null,
    public val endUnitNameZh: String? = null,
    public val teachers: List<SessionTeacher> = emptyList(),
    public val room: Room? = null,
    public val customPlace: String? = null,
    public val kind: SessionKind,
    public val state: SessionState? = null,
    public val periods: Int? = null,
    public val experimentProject: ExperimentProject? = null,
) {
    /** 上课地点展示文本：有固定教室时拼接校区、教学楼与教室名称，否则返回 [customPlace]。 */
    public val displayPlace: String?
        get() = room?.let { "${it.campus.nameZh} ${it.building.nameZh} ${it.nameZh}" } ?: customPlace
}

/**
 * 关联教学班与单次排课的只读视图。
 *
 * @property teachingClass 所属教学班。
 * @property session 单次排课场次。
 */
@Poko
public class SessionView(
    public val teachingClass: TeachingClass,
    public val session: ClassSession,
) {
    /** 所属课程基础元数据。 */
    public val course: Course
        get() = teachingClass.course

    /**
     * 用于展示的课程标题。
     *
     * 实验项目排课返回 `"课程名 - 实验项目名"`（两者相同时不重复拼接），常规课程返回课程中文名。
     */
    public val displayTitle: String
        get() {
            val project = session.experimentProject
            return if (project != null && project.nameZh != course.nameZh) {
                "${course.nameZh} - ${project.nameZh}"
            } else {
                course.nameZh
            }
        }

    /** 用于展示的上课地点文本。 */
    public val displayPlace: String?
        get() = session.displayPlace
}

/**
 * 同一时间段内发生重叠的排课冲突。
 *
 * @property startTime 冲突重叠区间的开始时刻（含）。
 * @property endTime 冲突重叠区间的结束时刻（不含）。
 * @property sessions 在 `[startTime, endTime)` 内同时重叠的全部排课视图。
 */
@Poko
public class SessionConflict(
    public val startTime: Instant,
    public val endTime: Instant,
    public val sessions: List<SessionView>,
)

/**
 * 将 [Timetable] 导出为 `iCalendar`（`.ics`）时的筛选与展示配置。
 *
 * @property weeks 仅导出指定教学周列表（从 1 开始，不得含 `0`）；为 `null` 或空时导出全部周次。
 * @property classIds 仅导出指定教学班 ID 列表；为 `null` 或空时导出全部教学班。
 * @property startDate 仅导出不早于该公历日期的排课（含当日）。
 * @property endDate 仅导出不晚于该公历日期的排课（含当日），不得早于 [startDate]。
 * @property alarmMinutesBefore 课前提醒提前分钟数列表（如 `[15]` 表示课前 15 分钟提醒）；空列表不生成提醒。
 * @property calendarName 日历显示名称（写入 `X-WR-CALNAME`）；省略或空白时默认使用学期中文名称。
 */
@Poko
@Serializable
public class CalendarExportOptions(
    public val weeks: List<Int>? = null,
    public val classIds: List<Long>? = null,
    public val startDate: LocalDate? = null,
    public val endDate: LocalDate? = null,
    public val alarmMinutesBefore: List<Int> = emptyList(),
    public val calendarName: String? = null,
)

/**
 * 指定教学周的课表只读视图。
 *
 * @property weekIndex 本视图对应的教学周序号，从 1 开始。
 */
public class WeekView internal constructor(
    private val timetable: Timetable,
    public val weekIndex: Int,
) {
    /** 返回本教学周在校历中对应的完整 7 天日期闭区间；超出校历范围时返回 `null`。 */
    public fun dateRange(): ClosedRange<LocalDate>? = timetable.semester.weekDateRange(weekIndex)

    /** 返回本周排课的最大节次；无课时返回 `0`。 */
    public fun maxUnit(): Int = timetable.sessions
        .asSequence()
        .filter { it.weekIndex == weekIndex }
        .maxOfOrNull { it.endUnit }
        ?: 0

    /** 返回本周全部排课视图，按上课时间升序排列。 */
    public fun sessions(): List<SessionView> = timetable.sessions
        .asSequence()
        .filter { it.weekIndex == weekIndex }
        .mapNotNull(timetable::viewOf)
        .toList()

    /** 返回本周指定星期 [weekday] 的全部排课视图。 */
    public fun day(weekday: DayOfWeek): List<SessionView> = timetable.sessions
        .asSequence()
        .filter { it.weekIndex == weekIndex && it.weekday == weekday }
        .mapNotNull(timetable::viewOf)
        .toList()

    /**
     * 返回本周 [weekday] 第 [unit] 节上的全部重叠排课视图。
     *
     * @throws IllegalArgumentException [unit] 小于 1 时。
     */
    public operator fun get(weekday: DayOfWeek, unit: Int): List<SessionView> {
        require(unit >= 1) { "节次从 1 开始，实际为 $unit" }
        return timetable.sessions
            .asSequence()
            .filter {
                it.weekIndex == weekIndex &&
                    it.weekday == weekday &&
                    unit in it.startUnit..it.endUnit
            }
            .mapNotNull(timetable::viewOf)
            .toList()
    }

    /** 检测本周内按真实时间区间 `[startTime, endTime)` 重叠的全部排课冲突。 */
    public fun conflicts(): List<SessionConflict> = detectConflicts(sessions())
}

/**
 * 单个学期的完整课表快照。
 *
 * [sessions] 按上课时间升序排列，每条 [ClassSession.classId] 均对应 [classes] 中的某一项。
 * 按周查看、冲突检测与日历导出均在本地完成，不发起网络请求。
 *
 * @property semester 课表所属学期校历。
 * @property classes 本学期全部选修教学班，包含未排课课程与实验父班。
 * @property sessions 本学期全部排课场次，按上课时间升序排列。
 */
@Poko
@Serializable
@Suppress("TooManyFunctions") // 课表快照的全部本地查询与导出入口集中在 Timetable 上，与 libzzu API 对齐。
public class Timetable(
    public val semester: Semester,
    public val classes: List<TeachingClass>,
    public val sessions: List<ClassSession>,
) {
    /** 按教学班 ID [classId] 查找教学班元数据。 */
    public fun teachingClass(classId: Long): TeachingClass? =
        classes.firstOrNull { it.id == classId }

    /** 返回尚未排课的教学班列表。 */
    public fun unscheduledClasses(): List<TeachingClass> {
        val scheduledIds = sessions.mapTo(HashSet()) { it.classId }
        return classes.filter { it.id !in scheduledIds }
    }

    /** 汇总本学期所有教学班的学分总和，忽略学分为 `null` 的条目。 */
    public fun totalCredits(): Double = classes.sumOf { it.course.credits ?: 0.0 }

    /** 计算公历日期 [date] 在本学期中对应的教学周序号；不属于本学期时返回 `null`。 */
    public fun weekOf(date: LocalDate): Int? = semester.weekOf(date)

    /**
     * 返回教学周 [weekIndex]（从 1 开始）的只读视图。
     *
     * @throws IllegalArgumentException [weekIndex] 小于 1 时。
     */
    public fun week(weekIndex: Int): WeekView {
        require(weekIndex >= 1) { "教学周从 1 开始，实际为 $weekIndex" }
        return WeekView(this, weekIndex)
    }

    /** 返回本课表涉及的全部教学周序号（校历周次与实际排课周次的升序并集）。 */
    public fun weekIndices(): List<Int> = buildSet {
        addAll(semester.weekIndices)
        sessions.mapTo(this) { it.weekIndex }
    }.sorted()

    /** 返回公历日期 [date] 的全部排课视图，按上课时间升序排列。 */
    public fun sessionsOn(date: LocalDate): List<SessionView> = sessions
        .asSequence()
        .filter { it.date == date }
        .mapNotNull(::viewOf)
        .toList()

    /**
     * 返回与半开时间区间 `[start, end)` 存在重叠的全部排课视图。
     *
     * 相邻边界（前一场次结束时刻恰好等于 [start]）不视为重叠。
     *
     * @throws IllegalArgumentException [start] 不早于 [end] 时。
     */
    public fun sessionsBetween(start: Instant, end: Instant): List<SessionView> {
        require(start < end) { "查询结束时间必须晚于开始时间" }
        return sessions
            .asSequence()
            .filter { it.startTime < end && it.endTime > start }
            .mapNotNull(::viewOf)
            .toList()
    }

    /** 返回在时刻 [at] 正在进行（`startTime <= at < endTime`）的全部排课视图。 */
    public fun currentSessions(at: Instant): List<SessionView> = sessions
        .asSequence()
        .filter { it.startTime <= at && at < it.endTime }
        .mapNotNull(::viewOf)
        .toList()

    /** 返回开始时刻严格晚于 [after] 的下一场排课视图。 */
    public fun nextSession(after: Instant): SessionView? = sessions
        .firstOrNull { it.startTime > after }
        ?.let(::viewOf)

    /** 检测整张课表内按真实时间区间 `[startTime, endTime)` 重叠的全部排课冲突。 */
    public fun conflicts(): List<SessionConflict> = detectConflicts(sessions.mapNotNull(::viewOf))

    /**
     * 按配置 [options] 将课表导出为 `iCalendar`（`.ics`）文本，只做本地计算，无需客户端或登录。
     *
     * 导出的事件保留合上教师、实验项目标题与课程学分，并按排课唯一标识去重。
     * 本方法在当前线程同步执行，整学期课表宜放到后台调度器上调用。
     *
     * @throws com.illustar0.zzu.ZzuException.InvalidInput [CalendarExportOptions.weeks] 包含 `0`，
     * 或 [CalendarExportOptions.startDate] 晚于 [CalendarExportOptions.endDate] 时。
     * @throws com.illustar0.zzu.ZzuException 课表数据不被 `libzzu` 接受时。
     */
    public fun toICalendar(options: CalendarExportOptions = CalendarExportOptions()): String =
        nativeTimetableToCalendar(
            buildJsonObject {
                putValue("timetable", this@Timetable)
                putValue("options", options)
            },
        )

    internal fun viewOf(session: ClassSession): SessionView? =
        teachingClass(session.classId)?.let { SessionView(it, session) }
}

/** 按时间端点切分重叠片段，避免把不直接冲突的相邻课程合并到同一组。 */
internal fun detectConflicts(sessions: List<SessionView>): List<SessionConflict> {
    if (sessions.size < 2) return emptyList()
    val timeline = buildSet {
        sessions.forEach { view ->
            add(view.session.startTime)
            add(view.session.endTime)
        }
    }.sorted()

    val conflicts = mutableListOf<SessionConflict>()
    for (index in 0 until timeline.lastIndex) {
        val segStart = timeline[index]
        val segEnd = timeline[index + 1]
        val active = sessions.filter { view ->
            view.session.startTime <= segStart && view.session.endTime >= segEnd
        }
        if (active.size < 2) continue
        val last = conflicts.lastOrNull()
        if (last != null && last.endTime == segStart && hasSameSessions(last.sessions, active)) {
            conflicts[conflicts.lastIndex] = SessionConflict(last.startTime, segEnd, last.sessions)
        } else {
            conflicts += SessionConflict(segStart, segEnd, active)
        }
    }
    return conflicts
}

private fun hasSameSessions(left: List<SessionView>, right: List<SessionView>): Boolean =
    left.size == right.size && left.zip(right).all { (a, b) -> a.session === b.session }

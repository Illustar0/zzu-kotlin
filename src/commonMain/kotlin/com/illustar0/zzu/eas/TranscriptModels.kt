package com.illustar0.zzu.eas

import dev.drewhamilton.poko.Poko
import kotlin.jvm.JvmInline
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * 课程修读性质，如必修、选修。
 *
 * 已知取值以 `snake_case` 常量暴露；遇到教务上游新增的其他字符串时会原样保留在 [value] 中。
 *
 * @property value 规范化后的修读性质标识文本。
 */
@JvmInline
@Serializable
public value class StudyType(public val value: String) {
    public companion object {
        /** 必修。 */
        public val REQUIRED: StudyType = StudyType("required")

        /** 选修。 */
        public val ELECTIVE: StudyType = StudyType("elective")
    }
}

/**
 * 课程成绩记录的展示与考核状态。
 *
 * 已知取值以 `snake_case` 常量暴露；遇到教务上游新增的其他字符串时会原样保留在 [value] 中。
 *
 * @property value 规范化后的状态标识文本。
 */
@JvmInline
@Serializable
public value class GradeState(public val value: String) {
    public companion object {
        /** 常规状态，无评教锁定或缓考待考标记。 */
        public val NORMAL: GradeState = GradeState("normal")

        /** 尚未完成网上评教，教务系统隐藏成绩与绩点。 */
        public val EVALUATION_REQUIRED: GradeState = GradeState("evaluation_required")

        /** 已办理缓考且尚未参加考试。 */
        public val DEFERRED_PENDING: GradeState = GradeState("deferred_pending")
    }
}

/**
 * 成绩条目关联的学期摘要元数据。
 *
 * 教务成绩接口内嵌的学期对象不含教学周列表，因此独立于 [Semester] 建模；可通过 [id] 与 [Semester.id] 关联。
 *
 * @property id 学期 ID。
 * @property code 学期代码，如 `"2024-2025-1"`。
 * @property nameZh 学期中文名称。
 * @property nameEn 学期英文名称。
 * @property schoolYear 所属学年，如 `"2024-2025"`。
 * @property season 学期季节。
 * @property startDate 学期起始公历日期（含）。
 * @property endDate 学期结束公历日期（含）。
 */
@Poko
@Serializable
@Suppress("LongParameterList") // 构造参数与 libzzu 的成绩学期摘要 JSON 字段一一对应。
public class GradeSemester(
    public val id: SemesterId,
    public val code: String,
    public val nameZh: String,
    public val nameEn: String? = null,
    public val schoolYear: String,
    public val season: SemesterSeason,
    public val startDate: LocalDate,
    public val endDate: LocalDate,
)

/**
 * 分项成绩下的过程性考核细项，如考勤、课堂测验与实验报告。
 *
 * @property name 细项名称。
 * @property score 细项成绩文本，如 `"95"`、`"优"`、`"缓考"`。
 */
@Poko
@Serializable
public class ProcessGrade(
    public val name: String,
    public val score: String? = null,
)

/**
 * 课程分项成绩，如平时成绩、实验成绩与期末成绩。
 *
 * @property kind 分项成绩类别。
 * @property score 分项成绩文本，如 `"91"`、`"缓考"`。
 * @property scoreEn 分项成绩英文文本。
 * @property isPublished 教务端是否将该分项标记为已对学生公开。
 * @property items 该分项下的过程性考核细项列表。
 */
@Poko
@Serializable
public class SubGrade(
    public val kind: NamedEntity,
    public val score: String? = null,
    public val scoreEn: String? = null,
    public val isPublished: Boolean,
    public val items: List<ProcessGrade> = emptyList(),
)

/**
 * 专业年级累计绩点排名统计，满足 `1 <= rank <= total`。
 *
 * @property rank 当前学生在专业年级中的名次，从 1 开始且不超过 [total]。
 * @property total 参与排名的专业年级总人数，大于 0。
 */
@Poko
@Serializable
public class GpaRank(
    public val rank: Int,
    public val total: Int,
)

/**
 * 单门课程修读成绩记录。
 *
 * 当 [state] 为 [GradeState.EVALUATION_REQUIRED]（未评教锁定）时，成绩、绩点、通过状态与分项明细均为 `null` 或空列表，
 * 避免将上游占位零值误判为挂科。
 *
 * @property semester 修读所属学期摘要。
 * @property courseId 课程数字 ID。
 * @property courseCode 课程代码，如 `"CS101"`。
 * @property courseNameZh 课程中文名称。
 * @property courseNameEn 课程英文名称。
 * @property classId 教学班数字 ID。
 * @property classCode 教学班选课课号。
 * @property courseType 课程类别，如必修课、限选课、分类通识课。
 * @property courseProperty 课程属性。
 * @property cultivateType 培养类别，如主修、辅修。
 * @property studyType 课程修读性质，如必修、选修。
 * @property credits 课程学分，未评教锁定时为 `null`。
 * @property finalScore 最终总评成绩文本，未出分或未评教锁定时为 `null`。
 * @property gradePoint 课程绩点，无绩点制课程、未出分或未评教锁定时为 `null`。
 * @property isPassed 是否通过考核，未出分或未评教锁定时为 `null`。
 * @property isPublished 总评成绩是否已发布。
 * @property isRetake 是否为重修记录。
 * @property state 成绩展示与考核状态。
 * @property makeupScore 补考成绩文本，未参加补考或未评教锁定时为 `null`。
 * @property deferredScore 缓考成绩文本，未参加缓考或未评教锁定时为 `null`。
 * @property scoreDetail 教务汇总的成绩构成说明，如 `"期末成绩:45;补考:60"`。
 * @property subGrades 分项成绩明细列表。
 */
@Poko
@Serializable
@Suppress("LongParameterList") // 构造参数与 libzzu 的课程成绩 JSON 字段一一对应。
public class CourseGrade(
    public val semester: GradeSemester,
    public val courseId: Long? = null,
    public val courseCode: String,
    public val courseNameZh: String,
    public val courseNameEn: String? = null,
    public val classId: Long? = null,
    public val classCode: String? = null,
    public val courseType: NamedEntity? = null,
    public val courseProperty: NamedEntity? = null,
    public val cultivateType: NamedEntity? = null,
    public val studyType: StudyType? = null,
    public val credits: Double? = null,
    public val finalScore: String? = null,
    public val gradePoint: Double? = null,
    public val isPassed: Boolean? = null,
    public val isPublished: Boolean,
    public val isRetake: Boolean,
    public val state: GradeState = GradeState.NORMAL,
    public val makeupScore: String? = null,
    public val deferredScore: String? = null,
    public val scoreDetail: String? = null,
    public val subGrades: List<SubGrade> = emptyList(),
)

/**
 * 学生完整成绩单与累计专业绩点排名快照。
 *
 * @property gpa 累计平均学分绩点，尚未产生有效绩点时为 `null`。
 * @property rank 专业年级累计绩点排名，未开放排名或尚无有效排名数据时为 `null`。
 * @property grades 课程成绩列表，按学期起始日期倒序（最新学期在前）、同学期内按课程代码升序排列。
 */
@Poko
@Serializable
public class Transcript(
    public val gpa: Double? = null,
    public val rank: GpaRank? = null,
    public val grades: List<CourseGrade> = emptyList(),
) {
    /** 筛选指定学期 [semesterId] 下的全部课程成绩记录，保持原有顺序。 */
    public fun semesterGrades(semesterId: SemesterId): List<CourseGrade> =
        grades.filter { it.semester.id == semesterId }

    /**
     * 统计已通过考核的课程总学分。
     *
     * 同一 [CourseGrade.courseCode] 多次修读通过时取最高学分去重，并排除未评教锁定记录。
     */
    public fun earnedCredits(): Double = grades
        .asSequence()
        .filter { it.state != GradeState.EVALUATION_REQUIRED && it.isPassed == true }
        .mapNotNull { grade -> grade.credits?.takeIf { it > 0.0 }?.let { grade.courseCode to it } }
        .groupingBy { it.first }
        .fold(0.0) { best, (_, credits) -> maxOf(best, credits) }
        .values
        .sum()
}

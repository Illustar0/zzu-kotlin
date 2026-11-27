package com.illustar0.zzu.eas

import dev.drewhamilton.poko.Poko
import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * 培养方案模块或课程的修读完成状态。
 *
 * 已知取值以 `snake_case` 常量暴露；遇到教务上游新增的其他字符串时会原样保留在 [value] 中。
 *
 * @property value 规范化后的完成状态标识文本。
 */
@JvmInline
@Serializable
public value class CompletionStatus(public val value: String) {
    public companion object {
        /** 已通过。 */
        public val PASSED: CompletionStatus = CompletionStatus("passed")

        /** 未通过。 */
        public val FAILED: CompletionStatus = CompletionStatus("failed")

        /** 修读中。 */
        public val IN_PROGRESS: CompletionStatus = CompletionStatus("in_progress")

        /** 尚未修读。 */
        public val NOT_TAKEN: CompletionStatus = CompletionStatus("not_taken")

        /** 免修。 */
        public val EXEMPT: CompletionStatus = CompletionStatus("exempt")
    }
}

/**
 * 培养方案内或计划外的单门课程修读进度。
 *
 * @property courseId 课程数字 ID。
 * @property courseCode 课程代码，如 `"CS101"`。
 * @property courseNameZh 课程中文名称。
 * @property courseNameEn 课程英文名称。
 * @property credits 课程学分。
 * @property courseType 课程类别，如必修课、限选课、核心通识课。
 * @property isCompulsory 是否为方案内强制必修课程。
 * @property suggestedTerms 升序排列的推荐修读学期序号列表（从 1 开始），如 `[1, 2]` 表示第一、二学期。
 * @property status 当前修读完成状态。
 * @property score 课程成绩文本，未修或未出分时为 `null`。
 * @property gradePoint 课程绩点，无绩点制或未出分时为 `null`。
 * @property remark 计划转换说明或备注。
 * @property substituteCourses 已生效的替代课程列表。
 */
@Poko
@Serializable
@Suppress("LongParameterList") // 构造参数与 libzzu 的培养方案课程 JSON 字段一一对应。
public class PlanCourse(
    public val courseId: Long,
    public val courseCode: String,
    public val courseNameZh: String,
    public val courseNameEn: String? = null,
    public val credits: Double? = null,
    public val courseType: NamedEntity? = null,
    public val isCompulsory: Boolean,
    public val suggestedTerms: List<Int> = emptyList(),
    public val status: CompletionStatus,
    public val score: String? = null,
    public val gradePoint: Double? = null,
    public val remark: String? = null,
    public val substituteCourses: List<NamedEntity> = emptyList(),
)

/**
 * 培养方案课程模块节点，支持多级子模块递归嵌套。
 *
 * @property kind 模块类别，如必修课、限选课、院任选课、校任选课。
 * @property requiredCredits 本模块要求修满的学分。
 * @property requiredCourseCount 本模块要求修满的课程门数，无门数限制时为 `0`。
 * @property requiredSubModuleCount 本模块要求达标的子模块数量，无子模块限制时为 `0`。
 * @property passedCredits 已通过审核的学分。
 * @property takingCredits 当前在修学分。
 * @property exemptCredits 免修学分。
 * @property status 本模块当前整体达标状态。
 * @property remark 模块备注说明。
 * @property children 下属子模块列表。
 * @property courses 归属本模块的课程列表。
 */
@Poko
@Serializable
@Suppress("LongParameterList") // 构造参数与 libzzu 的培养方案模块 JSON 字段一一对应。
public class PlanModule(
    public val kind: NamedEntity,
    public val requiredCredits: Double,
    public val requiredCourseCount: Int,
    public val requiredSubModuleCount: Int,
    public val passedCredits: Double,
    public val takingCredits: Double,
    public val exemptCredits: Double,
    public val status: CompletionStatus,
    public val remark: String? = null,
    public val children: List<PlanModule> = emptyList(),
    public val courses: List<PlanCourse> = emptyList(),
)

/**
 * 单个培养类别（如主修、辅修）的培养方案整体完成进度。
 *
 * @property cultivateType 培养类别，如主修、辅修。
 * @property requiredCredits 方案毕业要求总学分。
 * @property requiredModuleCount 方案要求达标的顶层模块数。
 * @property modules 顶层课程模块列表。
 * @property outOfPlanCourses 计划外课程及学分转换记录列表。
 */
@Poko
@Serializable
public class ProgramCompletion(
    public val cultivateType: NamedEntity,
    public val requiredCredits: Double,
    public val requiredModuleCount: Int,
    public val modules: List<PlanModule> = emptyList(),
    public val outOfPlanCourses: List<PlanCourse> = emptyList(),
) {
    /** 汇总顶层模块的已通过总学分。 */
    public fun passedCredits(): Double = modules.sumOf { it.passedCredits }

    /** 汇总顶层模块的当前在修总学分。 */
    public fun takingCredits(): Double = modules.sumOf { it.takingCredits }
}

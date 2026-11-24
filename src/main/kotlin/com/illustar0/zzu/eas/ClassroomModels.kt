package com.illustar0.zzu.eas

import com.illustar0.zzu.ZzuTimeZone
import com.illustar0.zzu.internal.ZonedInstantSerializer
import dev.drewhamilton.poko.Poko
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 校区元数据。
 *
 * @property id 校区数字 ID。
 * @property code 校区编码。
 * @property nameZh 校区中文名称。
 * @property nameEn 校区英文名称。
 * @property remark 备注信息。
 * @property isEnabled 是否启用。
 */
@Poko
@Serializable
public class Campus(
    public val id: Long,
    public val code: String,
    public val nameZh: String,
    public val nameEn: String? = null,
    public val remark: String? = null,
    @SerialName("enabled")
    public val isEnabled: Boolean,
)

/**
 * 教学楼元数据。
 *
 * @property id 教学楼数字 ID。
 * @property code 教学楼编码。
 * @property nameZh 教学楼中文名称。
 * @property nameEn 教学楼英文名称。
 * @property campusId 所属校区 ID，可能指向未出现在 [ClassroomCatalog.campuses] 中的默认校区 ID。
 * @property remark 备注信息。
 * @property isEnabled 是否启用。
 */
@Poko
@Serializable
@Suppress("LongParameterList") // 构造参数与 libzzu 的教学楼 JSON 字段一一对应。
public class Building(
    public val id: Long,
    public val code: String,
    public val nameZh: String,
    public val nameEn: String? = null,
    public val campusId: Long,
    public val remark: String? = null,
    @SerialName("enabled")
    public val isEnabled: Boolean,
)

/**
 * 教室类型元数据，如普通教室、多媒体教室与智慧教室。
 *
 * @property id 教室类型数字 ID。
 * @property code 教室类型编码。
 * @property nameZh 教室类型中文名称。
 * @property nameEn 教室类型英文名称。
 * @property isEnabled 是否启用。
 */
@Poko
@Serializable
public class RoomType(
    public val id: Long,
    public val code: String,
    public val nameZh: String,
    public val nameEn: String? = null,
    @SerialName("enabled")
    public val isEnabled: Boolean,
)

/**
 * 教室占用查询所需的筛选目录快照。
 *
 * 各列表保持扁平结构，便于构建界面筛选项，也便于通过 [ClassroomOccupancy.buildingId] 反查教学楼与校区信息。
 *
 * @property campuses 全部校区列表。
 * @property buildings 全部教学楼列表。
 * @property roomTypes 全部教室类型列表。
 * @property floors 可选楼层列表。
 */
@Poko
@Serializable
public class ClassroomCatalog(
    public val campuses: List<Campus>,
    public val buildings: List<Building>,
    public val roomTypes: List<RoomType>,
    public val floors: List<Int>,
)

/**
 * 教室占用查询的筛选条件。
 *
 * @property date 查询公历日期。
 * @property campusId 校区 ID，`null` 表示不限校区。
 * @property buildingIds 教学楼 ID 列表，空列表表示不限教学楼。
 * @property roomTypeIds 教室类型 ID 列表，空列表表示不限教室类型。
 * @property floors 楼层列表，空列表表示不限楼层。
 * @property minSeats 最小座位数，`null` 表示不限下限。
 * @property maxSeats 最大座位数，`null` 表示不限上限；同时指定时须不小于 [minSeats]。
 */
@Poko
@Serializable
@Suppress("LongParameterList") // 构造参数与 libzzu 的教室筛选条件字段一一对应。
public class ClassroomQuery(
    public val date: LocalDate,
    public val campusId: Long? = null,
    public val buildingIds: List<Long> = emptyList(),
    public val roomTypeIds: List<Long> = emptyList(),
    public val floors: List<Int> = emptyList(),
    public val minSeats: Int? = null,
    public val maxSeats: Int? = null,
)

/**
 * 全量并发拉取教室占用时的分页与并发配置。
 *
 * 上游接口响应耗时随 [pageSize] 增大而非线性上升（`100` 条约需 1 秒以上，`1000` 条约需 25 秒以上）；
 * 一般而言 [DEFAULT_PAGE_SIZE]（`44`）是单条均摊耗时最低的甜点值。
 * 若不了解后端特性，建议保持默认值；高占用率校区可酌情调小以降低单页尾延迟。
 *
 * @property pageSize 单次分页请求条数，须大于 0，默认为 [DEFAULT_PAGE_SIZE]。
 * @property concurrency 拉取剩余分页时的最大并发请求数，须大于 0，默认为 [DEFAULT_CONCURRENCY]。
 */
@Poko
@Serializable
public class ClassroomFetchOptions(
    public val pageSize: Int = DEFAULT_PAGE_SIZE,
    public val concurrency: Int = DEFAULT_CONCURRENCY,
) {
    public companion object {
        /** 教室占用分页查询的默认每页条数。 */
        public const val DEFAULT_PAGE_SIZE: Int = 44

        /** 并发拉取剩余分页时的默认最大并发请求数。 */
        public const val DEFAULT_CONCURRENCY: Int = 8
    }
}

/**
 * 教室占用活动类型。
 *
 * 已知取值以 `snake_case` 常量暴露；遇到教务上游新增的其他字符串时会原样保留在 [value] 中。
 *
 * @property value 规范化后的活动类型标识文本。
 */
@JvmInline
@Serializable
public value class RoomActivityType(public val value: String) {
    public companion object {
        /** 常规课程排课。 */
        public val LESSON: RoomActivityType = RoomActivityType("lesson")

        /** 实验项目排课。 */
        public val EXPERIMENT: RoomActivityType = RoomActivityType("experiment")

        /** 教室借用。 */
        public val ROOM_BORROW: RoomActivityType = RoomActivityType("room_borrow")
    }
}

/**
 * 教室在指定日期内的单条占用记录。
 *
 * 占用时段以半开区间 `[startTime, endTime)` 表示；[startTime] 与 [endTime] 须用 [ZzuTimeZone] 换算为当地时间。
 *
 * @property activityId 关联活动 ID，实验项目等场景可能为 `null`。
 * @property activityType 占用活动类型。
 * @property activityName 占用活动中文名称或说明。
 * @property activityNameEn 占用活动英文名称或说明。
 * @property date 占用公历日期。
 * @property startTime 占用开始时刻。
 * @property endTime 占用结束时刻，严格晚于 [startTime]。
 * @property teacherName 授课教师或借用负责人中文姓名，未提供时为空字符串。
 * @property teacherNameEn 授课教师或借用负责人英文姓名。
 */
@Poko
@Serializable
@Suppress("LongParameterList") // 构造参数与 libzzu 的教室占用记录 JSON 字段一一对应。
public class RoomOccupation(
    public val activityId: Long? = null,
    public val activityType: RoomActivityType,
    public val activityName: String,
    public val activityNameEn: String? = null,
    public val date: LocalDate,
    @Serializable(with = ZonedInstantSerializer::class)
    public val startTime: Instant,
    @Serializable(with = ZonedInstantSerializer::class)
    public val endTime: Instant,
    public val teacherName: String,
    public val teacherNameEn: String? = null,
)

/**
 * 教室基础信息及其在查询当日的占用列表。
 *
 * @property id 教室数字 ID。
 * @property code 教室编码。
 * @property nameZh 教室中文名称。
 * @property nameEn 教室英文名称。
 * @property buildingId 所属教学楼 ID，可通过 [ClassroomCatalog.buildings] 查找教学楼名称。
 * @property campusNameZh 所属校区中文名称。
 * @property occupations 查询当日的占用记录列表，无占用时为空列表。
 */
@Poko
@Serializable
@Suppress("LongParameterList") // 构造参数与 libzzu 的教室占用 JSON 字段一一对应。
public class ClassroomOccupancy(
    public val id: Long,
    public val code: String,
    public val nameZh: String,
    public val nameEn: String? = null,
    public val buildingId: Long,
    public val campusNameZh: String,
    public val occupations: List<RoomOccupation> = emptyList(),
) {
    /**
     * 判断该教室在半开时间区间 `[start, end)` 内是否完全空闲。
     *
     * 相邻边界（前一条占用的结束时刻恰好等于 [start]）不视为冲突。
     *
     * @throws IllegalArgumentException [start] 不早于 [end] 时。
     */
    public fun isFreeBetween(start: Instant, end: Instant): Boolean =
        occupationsBetween(start, end).isEmpty()

    /**
     * 返回该教室在半开时间区间 `[start, end)` 内存在重叠的全部占用记录。
     *
     * @throws IllegalArgumentException [start] 不早于 [end] 时。
     */
    public fun occupationsBetween(start: Instant, end: Instant): List<RoomOccupation> {
        require(start < end) { "查询结束时间必须晚于开始时间" }
        return occupations.filter { it.startTime < end && it.endTime > start }
    }
}

/**
 * 教室占用单页查询结果。
 *
 * @property items 当前页返回的教室占用列表。
 * @property page 当前页码，从 1 开始。
 * @property pageSize 本次实际使用的每页条数。
 * @property totalItems 符合筛选条件的教室总条数。
 * @property totalPages 总页数。
 */
@Poko
@Serializable
public class ClassroomPage(
    public val items: List<ClassroomOccupancy>,
    public val page: Int,
    public val pageSize: Int,
    public val totalItems: Int,
    public val totalPages: Int,
) {
    /** 当前页之后是否还有更多分页。 */
    public val hasNext: Boolean
        get() = page < totalPages
}

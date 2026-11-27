package com.illustar0.zzu.eas

import com.illustar0.zzu.ZzuException
import com.illustar0.zzu.cas.CasClient
import com.illustar0.zzu.internal.ZzuRpc
import com.illustar0.zzu.internal.fetch
import com.illustar0.zzu.internal.putValue
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 本科教务系统（EAMS）客户端，用于查询学期校历、学期完整课表、成绩单与绩点排名以及教室占用。
 *
 * 共享 [CasClient] 的统一身份认证会话与操作时限，线程安全，实现 [AutoCloseable]，用完后须调用 [close] 释放句柄，
 * 在途调用不受影响。只读查询方法可并发执行，[login] 与 [logout] 与之互斥同步。
 *
 * 除 [login]、[logout]、[isLoggedIn] 外的方法都要求先完成 [login]。
 * 所有挂起函数都可在主线程调用，失败时抛出 [ZzuException]；取消协程会同步取消底层 `libzzu` 调用。
 */
public class EasClient internal constructor(private val rpc: ZzuRpc) : AutoCloseable by rpc {
    /**
     * 验证教务门户会话并拉取、缓存当前学期元数据，需先完成 [CasClient.login]。
     *
     * @throws ZzuException.Authentication 底层 [CasClient] 尚未登录或令牌不可用时（`NOT_AUTHENTICATED` 或 `TOKEN_EXPIRED`）。
     * @throws ZzuException.Transport 网络传输失败时。
     * @throws ZzuException.Http 教务门户或当前学期接口返回非 2xx 状态码时。
     * @throws ZzuException.Remote 服务端返回业务错误状态时。
     * @throws ZzuException.Decode 响应解析失败时。
     */
    public suspend fun login() {
        rpc.perform("login")
    }

    /** 清除本地缓存的教务当前学期与登录状态，不影响底层共享的 [CasClient]。 */
    public suspend fun logout() {
        rpc.perform("logout")
    }

    /** 返回教务客户端是否已登录并缓存了当前学期元数据。 */
    public suspend fun isLoggedIn(): Boolean = rpc.fetch("is_logged_in")

    /**
     * 返回 [login] 时缓存的当前学期元数据，不发起网络请求。
     *
     * @throws ZzuException.Authentication 尚未调用 [login] 时 `kind` 为
     * [AuthenticationErrorKind.NOT_AUTHENTICATED][com.illustar0.zzu.AuthenticationErrorKind.NOT_AUTHENTICATED]。
     */
    public suspend fun currentSemester(): Semester = rpc.fetch("current_semester")

    /**
     * 查询当前学生可见的全部学期列表。
     *
     * @throws ZzuException.Authentication 教务客户端尚未登录或 CAS 令牌不可用时。
     * @throws ZzuException.Transport 网络传输失败时。
     * @throws ZzuException.Remote 远端返回业务错误状态时。
     * @throws ZzuException.Decode 学期列表解析失败时。
     */
    public suspend fun semesters(): List<Semester> = rpc.fetch("semesters")

    /**
     * 查询 [semesterId] 指定学期的完整课表；[semesterId] 为 `null` 时取 [currentSemester]。
     *
     * 当前学期直接复用登录时缓存的学期元数据，其他学期会先拉取学期列表。
     * [includeExperiments] 控制是否包含实验系统排课，默认为 `true`。
     * 返回的 [Timetable] 包含学期校历、匹配的每日节次作息、全部教学班与按时间升序排列的排课场次；
     * 按周查看、冲突检测与日历导出均在本地完成，不发起网络请求。
     *
     * @throws ZzuException.Authentication 教务客户端尚未登录或 CAS 令牌不可用时。
     * @throws ZzuException.NotFound 指定的 [semesterId] 在学期列表中不存在时。
     * @throws ZzuException.Remote 远端返回业务错误状态时。
     * @throws ZzuException.Decode 课表时间、节次或响应解析校验失败时。
     * @throws ZzuException.Transport 网络传输失败时。
     */
    public suspend fun timetable(
        semesterId: SemesterId? = null,
        includeExperiments: Boolean = true,
    ): Timetable = rpc.fetch(
        "timetable",
        buildJsonObject {
            put("semesterId", semesterId?.value)
            put("includeExperiments", includeExperiments)
        },
    )

    /**
     * 并发查询当前学生的完整成绩单与累计专业绩点排名。
     *
     * 结果按最新学期在前排序；尚未完成网上评教的课程自动将 [CourseGrade.state] 标为
     * [GradeState.EVALUATION_REQUIRED]，并隐藏占位零分与绩点。
     *
     * @throws ZzuException.Authentication 教务客户端尚未登录或 CAS 令牌不可用时。
     * @throws ZzuException.Remote 远端返回业务错误状态时。
     * @throws ZzuException.Decode 成绩或绩点排名字段解析校验失败时。
     * @throws ZzuException.Transport 网络传输失败时。
     */
    public suspend fun transcript(): Transcript = rpc.fetch("transcript")

    /**
     * 并发查询教室占用筛选所需的全部参照目录（校区、教学楼、教室类型、楼层与标准节次作息）。
     *
     * 返回扁平列表，供界面构建筛选项、绘制节次概览，以及按 [ClassroomOccupancy.buildingId] 反查教学楼信息。
     *
     * @throws ZzuException.Authentication 教务客户端尚未登录或 CAS 令牌不可用时。
     * @throws ZzuException.Remote 远端返回业务错误状态时。
     * @throws ZzuException.Decode 目录响应解析失败时。
     * @throws ZzuException.Transport 网络传输失败时。
     */
    public suspend fun classroomCatalog(): ClassroomCatalog = rpc.fetch("classroom_catalog")

    /**
     * 按页查询符合 [query] 筛选条件的教室及当日占用记录。
     *
     * [page] 从 1 开始，默认为 1；[pageSize] 为 `null` 时使用 [ClassroomFetchOptions.DEFAULT_PAGE_SIZE]（`44`）。
     * 上游接口耗时随 [pageSize] 增大而非线性上升，不了解后端特性时建议保持默认值。
     *
     * @throws ZzuException.InvalidInput [page] 小于 1、[pageSize] 小于 1，
     * 或 [ClassroomQuery.minSeats] 大于 [ClassroomQuery.maxSeats] 时。
     * @throws ZzuException.Authentication 教务客户端尚未登录或 CAS 令牌不可用时。
     * @throws ZzuException.Remote 远端返回业务错误状态时。
     * @throws ZzuException.Decode 占用时段校验或响应解析失败时。
     * @throws ZzuException.Transport 网络传输失败时。
     */
    public suspend fun classroomOccupanciesPage(
        query: ClassroomQuery,
        page: Int = 1,
        pageSize: Int? = null,
    ): ClassroomPage = rpc.fetch(
        "classroom_occupancies_page",
        buildJsonObject {
            putClassroomQuery(query)
            put("page", page)
            pageSize?.let { put("pageSize", it) }
        },
    )

    /**
     * 以有界并发拉取符合 [query] 筛选条件的全部教室及当日占用记录。
     *
     * 内部先按 [ClassroomFetchOptions.pageSize] 拉取第 1 页获知总页数，再按 [ClassroomFetchOptions.concurrency]
     * 并发拉取剩余分页，按页序合并并按教室 ID 去重；任一页失败会立即中止并返回错误。
     * 不带空间筛选的整校查询在高占用时段耗时较长，建议配合校区或教学楼筛选、放宽超时，
     * 或在需要进度展示与单页容错时改用 [classroomOccupanciesPage]。
     *
     * @throws ZzuException.InvalidInput [query]、[ClassroomFetchOptions.pageSize]
     * 或 [ClassroomFetchOptions.concurrency] 参数非法时。
     * @throws ZzuException.Authentication 教务客户端尚未登录或 CAS 令牌不可用时。
     * @throws ZzuException.Remote 远端返回业务错误状态时。
     * @throws ZzuException.Decode 占用时段校验或响应解析失败时。
     * @throws ZzuException.Transport 网络传输失败时。
     */
    public suspend fun classroomOccupancies(
        query: ClassroomQuery,
        options: ClassroomFetchOptions = ClassroomFetchOptions(),
    ): List<ClassroomOccupancy> = rpc.fetch(
        "classroom_occupancies",
        buildJsonObject {
            putClassroomQuery(query)
            put("pageSize", options.pageSize)
            put("concurrency", options.concurrency)
        },
    )
}

private fun JsonObjectBuilder.putClassroomQuery(query: ClassroomQuery) {
    put("date", query.date.toString())
    query.campusId?.let { put("campusId", it) }
    putValue("buildingIds", query.buildingIds)
    putValue("roomTypeIds", query.roomTypeIds)
    putValue("floors", query.floors)
    query.minSeats?.let { put("minSeats", it) }
    query.maxSeats?.let { put("maxSeats", it) }
}

/**
 * 创建与 [cas] 共享认证会话、后台运行时与操作时限的 [EasClient]。
 *
 * 仅在本地初始化，不发起网络请求；此后关闭 [cas] 不影响它继续使用。
 *
 * @throws ZzuException 原生客户端句柄初始化失败时。
 */
public fun EasClient(cas: CasClient): EasClient = EasClient(ZzuRpc(cas.transport.newEasTransport()))

package dev.zzu.eas

import dev.zzu.ZzuException
import dev.zzu.cas.CasClient
import dev.zzu.internal.ZzuRpc
import dev.zzu.internal.fetch
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 本科教务客户端，线程安全，用完后须 [close] 释放句柄，在途调用不受影响。
 *
 * 除 [login]、[logout]、[isLoggedIn] 外的方法都要求先完成 [login]。所有挂起函数都可在主线程调用，
 * 失败时抛 [ZzuException]，协程取消会传递到 libzzu。
 */
public class EasClient internal constructor(private val rpc: ZzuRpc) : AutoCloseable by rpc {
    /** 校验教务门户会话并缓存当前学期，需先完成 [CasClient.login]。 */
    public suspend fun login() {
        rpc.perform("login")
    }

    /** 清除教务登录态，不影响 CAS 会话。 */
    public suspend fun logout() {
        rpc.perform("logout")
    }

    /** 返回教务客户端是否已登录。 */
    public suspend fun isLoggedIn(): Boolean = rpc.fetch("is_logged_in")

    /** 返回 [login] 时缓存的当前学期，不发起网络请求。 */
    public suspend fun currentSemester(): Semester = rpc.fetch("current_semester")

    /** 查询全部学期。 */
    public suspend fun semesters(): List<Semester> = rpc.fetch("semesters")

    /**
     * 查询一个学期按校历排列的全部教学周课表。
     *
     * @param semesterId 目标学期，默认为当前学期。
     * @throws ZzuException.NotFound 学期不存在时。
     */
    public suspend fun teachingWeeks(semesterId: SemesterId? = null): List<TeachingWeek> =
        rpc.fetch("teaching_weeks", buildJsonObject { put("semesterId", semesterId?.value) })

    /**
     * 查询单周课表。
     *
     * @param week 教学周序号，从 1 开始，对应 [TeachingWeek.weekIndex]。
     * @param semesterId 目标学期，默认为当前学期。
     * @throws ZzuException.InvalidInput [week] 不在学期内时。
     */
    public suspend fun teachingWeek(week: Int, semesterId: SemesterId? = null): TeachingWeek =
        rpc.fetch(
            "teaching_week",
            buildJsonObject {
                put("week", week)
                put("semesterId", semesterId?.value)
            },
        )

    /** 返回 [date] 所属的教学周序号；假期等非教学周为 `null`。 */
    public suspend fun weekIndex(date: LocalDate): Int? =
        rpc.fetch("week_index", buildJsonObject { put("date", date.toString()) })
}

/** 创建与 [cas] 共享认证会话与时限的教务客户端，不会发起网络请求；此后关闭 [cas] 不影响它。 */
public fun EasClient(cas: CasClient): EasClient = EasClient(ZzuRpc(cas.transport.newEasTransport()))

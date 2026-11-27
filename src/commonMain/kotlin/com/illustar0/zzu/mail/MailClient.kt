package com.illustar0.zzu.mail

import com.illustar0.zzu.ZzuException
import com.illustar0.zzu.cas.CasClient
import com.illustar0.zzu.internal.ZzuRpc
import com.illustar0.zzu.internal.fetch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 校园邮箱（Coremail）客户端，用于获取网页版邮箱入口地址，并查询收件箱未读邮件数与邮件摘要列表。
 *
 * 共享 [CasClient] 的统一身份认证会话与操作时限，线程安全，实现 [AutoCloseable]，用完后须调用 [close] 释放句柄，
 * 在途调用不受影响。只读查询方法（[isLoggedIn]、[webmailUrl]、[unreadCount]、[messages]）可并发执行，
 * [login] 与 [logout] 与之互斥同步。
 *
 * 客户端不启动后台刷新任务：每次调用 [webmailUrl]、[unreadCount] 或 [messages] 前都会检查缓存新鲜度（20 分钟经验阈值）
 * 与底层 CAS 令牌；若会话超期、CAS 令牌已更新，或服务端返回会话失效错误，内部会合并换新会话并自动重试查询。
 * 若底层 CAS 令牌已不可用，则直接抛出 [ZzuException.Authentication]。
 *
 * 除 [login]、[logout]、[isLoggedIn] 外的方法都要求先完成 [login]。
 * 所有挂起函数都可在主线程调用，失败时抛出 [ZzuException]；取消协程会同步取消底层 `libzzu` 调用。
 */
public class MailClient internal constructor(private val rpc: ZzuRpc) : AutoCloseable by rpc {
    /**
     * 使用共享的 [CasClient] 令牌换取校园邮箱会话（`sid`）与网页版入口地址，需先完成 [CasClient.login]。
     *
     * @throws ZzuException.Authentication 底层 [CasClient] 尚未登录或令牌不可用时（`NOT_AUTHENTICATED` 或 `TOKEN_EXPIRED`）。
     * @throws ZzuException.Decode 单点登录跳转响应缺少 `Location` 或 `sid` 参数时。
     * @throws ZzuException.Transport 网络传输失败时。
     * @throws ZzuException.Http 服务端返回非 2xx/3xx 状态码时。
     */
    public suspend fun login() {
        rpc.perform("login")
    }

    /** 清除本地缓存的邮箱会话，不影响底层共享的 [CasClient]。 */
    public suspend fun logout() {
        rpc.perform("logout")
    }

    /** 返回校园邮箱客户端是否已建立本地会话。 */
    public suspend fun isLoggedIn(): Boolean = rpc.fetch("is_logged_in")

    /**
     * 获取当前邮箱会话对应的网页版入口 URL（含 `sid` 查询参数）。
     *
     * 返回的 URL 携带邮箱会话凭据，切勿写入日志。若缓存会话已超过 20 分钟或底层 CAS 令牌已更新，
     * 会自动按需换取新会话。
     *
     * @throws ZzuException.Authentication 尚未调用 [login] 或底层 CAS 令牌不可用时。
     * @throws ZzuException.Decode 单点登录跳转缺少 `Location` 或 `sid` 时。
     * @throws ZzuException.Transport 网络传输失败时。
     * @throws ZzuException.Http 服务端返回非 2xx/3xx 状态码时。
     */
    public suspend fun webmailUrl(): String = rpc.fetch("webmail_url")

    /**
     * 查询收件箱（文件夹 ID 为 `1`）中的未读邮件数。
     *
     * 统计口径与 Coremail 导航栏未读提示一致，仅包含收件箱，不计入虚拟视图、草稿箱、
     * 已发送、已删除、垃圾邮件与病毒文件夹。服务端报告会话失效时会自动用现有 CAS 令牌换新一次并重试。
     *
     * @throws ZzuException.Authentication 尚未调用 [login] 或底层 CAS 令牌不可用时。
     * @throws ZzuException.Remote 邮箱服务返回业务错误时。
     * @throws ZzuException.Decode 响应缺少收件箱统计字段或解析失败时。
     * @throws ZzuException.Transport 网络传输失败时。
     * @throws ZzuException.Http 服务端返回非 2xx 状态码且未附带可识别的 JSON 错误体时。
     */
    public suspend fun unreadCount(): Int = rpc.fetch("unread_count")

    /**
     * 分页查询收件箱（文件夹 ID 为 `1`）中的邮件摘要列表，按接收时间倒序排列。
     *
     * [page] 从 1 开始，默认为 1；[pageSize] 为 `null` 时使用 [MailMessagePage.DEFAULT_PAGE_SIZE]（`20`）。
     * 服务端报告会话失效时会自动用现有 CAS 令牌换新一次并重试；阅读完整正文或处理附件请跳转 [webmailUrl]。
     *
     * @throws ZzuException.InvalidInput [page] 小于 1 或 [pageSize] 小于 1 时。
     * @throws ZzuException.Authentication 尚未调用 [login] 或底层 CAS 令牌不可用时。
     * @throws ZzuException.Remote 邮箱服务返回业务错误时。
     * @throws ZzuException.Decode 邮件摘要字段解析失败时。
     * @throws ZzuException.Transport 网络传输失败时。
     * @throws ZzuException.Http 服务端返回非 2xx 状态码且未附带可识别的 JSON 错误体时。
     */
    public suspend fun messages(
        page: Int = 1,
        pageSize: Int? = null,
    ): MailMessagePage = rpc.fetch(
        "messages",
        buildJsonObject {
            put("page", page)
            pageSize?.let { put("pageSize", it) }
        },
    )
}

/**
 * 创建与 [cas] 共享认证会话、后台运行时与操作时限的 [MailClient]。
 *
 * 仅在本地初始化，不发起网络请求；此后关闭 [cas] 不影响它继续使用。
 *
 * @throws ZzuException 原生客户端句柄初始化失败时。
 */
public fun MailClient(cas: CasClient): MailClient =
    MailClient(ZzuRpc(cas.transport.newMailTransport()))

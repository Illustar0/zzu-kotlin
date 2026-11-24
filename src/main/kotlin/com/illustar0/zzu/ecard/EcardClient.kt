package com.illustar0.zzu.ecard

import com.illustar0.zzu.ZzuException
import com.illustar0.zzu.cas.CasClient
import com.illustar0.zzu.internal.DecimalSerializer
import com.illustar0.zzu.internal.ZzuRpc
import com.illustar0.zzu.internal.fetch
import com.illustar0.zzu.internal.putValue
import java.math.BigDecimal
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 校园卡与宿舍电控服务客户端，涵盖余额查询、电控位置检索、电费查询与充值以及校园卡网页充值下单。
 *
 * 共享 [CasClient] 的统一身份认证会话与操作时限，线程安全，实现 [AutoCloseable]，用完后须调用 [close] 释放句柄，
 * 在途调用不受影响。查询与收银台预支付方法（[isLoggedIn]、[defaultRoom]、[balance]、[locations]、
 * [remainingEnergy]、[rechargeConfig]、[paymentChannels]、[preparePayment]）只在读写缓存时短暂持锁，可并发执行；
 * [login]、[logout]、[rechargeEnergy] 与 [createRechargeOrder] 互斥同步执行。
 *
 * 客户端不启动后台刷新任务：每次请求前按需检查内部校园卡访问令牌，满 45 分钟或底层 CAS 用户令牌变更时自动换新，
 * 并发请求会合并复用同一次换新结果；若此时 CAS 令牌已不可用，则直接抛出 [ZzuException.Authentication]。
 *
 * 除 [login]、[logout]、[isLoggedIn]、[paymentChannels]、[preparePayment] 外的方法都要求先完成 [login]。
 * 所有挂起函数都可在主线程调用，失败时抛出 [ZzuException]；取消协程会同步取消底层 `libzzu` 调用。
 */
@Suppress("TooManyFunctions") // 校园卡与电控共用一个客户端会话，保持现有公开 API。
public class EcardClient internal constructor(private val rpc: ZzuRpc) : AutoCloseable by rpc {
    /**
     * 使用共享的 [CasClient] 令牌换取校园卡访问令牌并建立会话，需先完成 [CasClient.login]。
     *
     * @throws ZzuException.Authentication 底层 [CasClient] 尚未登录或令牌不可用时。
     * @throws ZzuException.Decode 单点登录跳转缺少 `Location` 或 `tid` 参数，或令牌交换响应解析失败时。
     * @throws ZzuException.Transport 网络传输失败时。
     */
    public suspend fun login() {
        rpc.perform("login")
    }

    /** 清除本地缓存的校园卡会话与默认房间信息，不影响底层共享的 [CasClient]。 */
    public suspend fun logout() {
        rpc.perform("logout")
    }

    /** 返回校园卡客户端是否已建立本地会话。 */
    public suspend fun isLoggedIn(): Boolean = rpc.fetch("is_logged_in")

    /**
     * 查询并缓存当前账号在电控系统中绑定的默认宿舍房间 ID，[logout] 时一并清除。
     *
     * @throws ZzuException.Authentication 尚未调用 [login] 时（`NOT_AUTHENTICATED`）或令牌失效时。
     * @throws ZzuException.Remote 远端返回业务错误时。
     * @throws ZzuException.Decode 房间号解析失败时。
     * @throws ZzuException.Transport 网络传输失败时。
     */
    public suspend fun defaultRoom(): RoomId = rpc.fetch("default_room")

    /**
     * 查询当前账号的校园卡余额，单位为元。
     *
     * @throws ZzuException.Authentication 尚未登录或底层 CAS 令牌不可用时。
     * @throws ZzuException.Crypto 获取临时公钥或 SM2/SM4 加解密失败时。
     * @throws ZzuException.Remote 远端返回业务错误时。
     * @throws ZzuException.Decode 余额字段缺失或不是合法十进制金额时。
     * @throws ZzuException.Transport 网络传输失败时。
     */
    public suspend fun balance(): BigDecimal = rpc.fetch("balance", null, DecimalSerializer)

    /**
     * 按上级位置编码 [parent] 逐级检索电控位置目录，结果按 `id` 升序排列。
     *
     * [parent] 支持以下四种层级格式（其中 `area`、`building`、`level` 各段均非空且不含 `-`）：
     * - `""`（空字符串）：查询区域列表；
     * - `"area"`（如 `"1"`）：查询指定区域下的楼栋列表；
     * - `"area-building"`（如 `"1-2"`）：查询指定楼栋下的单元或楼层列表；
     * - `"area-building--level"`（如 `"1-2--3"`）：查询指定楼层下的房间列表，其 [Location.id]
     *   格式为 `area-building--level-room`，可用 [RoomId.parse] 解析。
     *
     * @throws ZzuException.InvalidInput [parent] 不符合上述四种格式（如含空段、多余连字符或传入四段完整房间号）时。
     * @throws ZzuException.Authentication 尚未登录或令牌不可用时。
     * @throws ZzuException.Remote 远端返回业务错误时。
     * @throws ZzuException.Transport 网络传输失败时。
     */
    public suspend fun locations(parent: String = ""): List<Location> =
        rpc.fetch("locations", buildJsonObject { put("parent", parent) })

    /**
     * 查询 [room] 的剩余电量，单位为度；[room] 为 `null` 时取 [defaultRoom]。
     *
     * @throws ZzuException.Authentication 尚未登录或令牌不可用时。
     * @throws ZzuException.Remote 获取默认房间或电费账户接口返回业务错误时。
     * @throws ZzuException.Decode 缺少 `quantity` 电量字段或数值解析失败时。
     * @throws ZzuException.Transport 网络传输失败时。
     */
    public suspend fun remainingEnergy(room: RoomId? = null): Double =
        rpc.fetch("remaining_energy", buildJsonObject { put("room", room?.value) })

    /**
     * 使用校园卡支付密码 [paymentPassword] 从卡余额扣款，为 [room] 充值 [amount] 元（正整数）电费。
     *
     * 提交前会申请一次性交易号与服务端临时 SM2 公钥，用公钥加密支付参数与随机生成的 SM4 密钥，
     * 并在收到加密响应时用该 SM4 密钥解密。
     *
     * 本操作**非幂等**：协程被取消、超时（[ZzuException.Timeout]），或收到 [ZzuException.Decode]（响应缺少 `success` 确认字段）、
     * [ZzuException.Crypto]（加密响应解密失败）时，服务端都可能已受理扣款。此时须先查询 [balance]，再决定是否重试。
     *
     * @throws ZzuException.InvalidInput [amount] 小于等于 0 或 [paymentPassword] 为空字符串时。
     * @throws ZzuException.Authentication 尚未登录或令牌不可用时。
     * @throws ZzuException.Remote 远端拒绝扣款（如密码错误或余额不足）时。
     * @throws ZzuException.Crypto 公钥解析或 SM2/SM4 加解密失败时。
     * @throws ZzuException.Decode 响应缺失确认字段或解析失败时。
     * @throws ZzuException.Timeout 操作超时时。
     */
    public suspend fun rechargeEnergy(paymentPassword: String, amount: Int, room: RoomId) {
        rpc.perform(
            "recharge_energy",
            buildJsonObject {
                put("paymentPassword", paymentPassword)
                put("amount", amount)
                put("room", room.value)
            },
        )
    }

    /**
     * 查询校园卡充值可选面额与网页收银台开放状态。
     *
     * @throws ZzuException.Authentication 尚未登录或令牌不可用时。
     * @throws ZzuException.Remote 远端返回业务错误时。
     * @throws ZzuException.Decode 面额字段解析失败时。
     * @throws ZzuException.Transport 网络传输失败时。
     */
    public suspend fun rechargeConfig(): RechargeConfig = rpc.fetch("recharge_config")

    /**
     * 创建指定整元金额 [amount]（单位为元，须大于 0）的校园卡网页充值订单。
     *
     * @throws ZzuException.InvalidInput [amount] 小于等于 0 时。
     * @throws ZzuException.Remote 校园卡服务未开放网页收银台充值或远端拒绝下单时。
     * @throws ZzuException.Authentication 尚未登录或令牌不可用时。
     * @throws ZzuException.Decode 返回的订单流水号或金额与请求不一致时。
     * @throws ZzuException.Transport 网络传输失败时。
     */
    public suspend fun createRechargeOrder(amount: Int): RechargeOrder =
        rpc.fetch("create_recharge_order", buildJsonObject { put("amount", amount) })

    /**
     * 查询充值订单 [order] 在学校收银台可用的支付渠道列表。
     *
     * 直接访问学校收银台接口，无需校园卡客户端登录。
     *
     * @throws ZzuException.Remote 收银台返回业务错误时。
     * @throws ZzuException.Decode 渠道列表解析失败时。
     * @throws ZzuException.Transport 网络传输失败时。
     */
    public suspend fun paymentChannels(order: RechargeOrder): List<PaymentChannel> =
        rpc.fetch("payment_channels", buildJsonObject { put("order", order.wire) })

    /**
     * 向学校收银台提交 [order] 与 [channel] 的预支付请求，返回拉起第三方支付所需的 [PaymentAction]。
     *
     * [context] 默认上报 [PaymentDevice.ANDROID] 与空 IP。直接访问学校收银台接口，无需校园卡客户端登录。
     *
     * @throws ZzuException.InvalidInput [PaymentChannel.id] 为空字符串时。
     * @throws ZzuException.Remote 收银台返回业务错误时。
     * @throws ZzuException.Decode 预支付响应中的跳转地址或表单不符合无凭据 HTTPS 规范，或格式解析失败时。
     * @throws ZzuException.Transport 网络传输失败时。
     */
    public suspend fun preparePayment(
        order: RechargeOrder,
        channel: PaymentChannel,
        context: PaymentContext = PaymentContext(),
    ): PaymentAction = rpc.fetch(
        "prepare_payment",
        buildJsonObject {
            put("order", order.wire)
            putValue("channel", channel)
            putValue("context", context)
        },
    )
}

/**
 * 创建与 [cas] 共享认证会话、后台运行时与操作时限的 [EcardClient]。
 *
 * 仅在本地初始化，不发起网络请求；此后关闭 [cas] 不影响它继续使用。
 *
 * @throws ZzuException 原生客户端句柄初始化失败时。
 */
public fun EcardClient(cas: CasClient): EcardClient =
    EcardClient(ZzuRpc(cas.transport.newEcardTransport()))

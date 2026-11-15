package dev.zzu.ecard

import dev.zzu.ZzuException
import dev.zzu.cas.CasClient
import dev.zzu.internal.DecimalSerializer
import dev.zzu.internal.ZzuRpc
import dev.zzu.internal.fetch
import dev.zzu.internal.putValue
import java.math.BigDecimal
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 校园卡与电控客户端，线程安全，用完后须 [close] 释放句柄，在途调用不受影响。
 *
 * 除 [login]、[logout]、[isLoggedIn]、[paymentChannels]、[preparePayment] 外的方法都要求先完成
 * [login]。所有挂起函数都可在主线程调用，失败时抛 [ZzuException]，协程取消会传递到 libzzu。
 */
@Suppress("TooManyFunctions") // 校园卡与电控共用一个客户端会话，保持现有公开 API。
public class EcardClient internal constructor(private val rpc: ZzuRpc) : AutoCloseable by rpc {
    /** 用 CAS 令牌换取校园卡会话，需先完成 [CasClient.login]；此后访问令牌按需自动换新。 */
    public suspend fun login() {
        rpc.perform("login")
    }

    /** 清除校园卡会话与缓存的默认房间，不影响 CAS 会话。 */
    public suspend fun logout() {
        rpc.perform("logout")
    }

    /** 返回校园卡客户端是否已建立会话。 */
    public suspend fun isLoggedIn(): Boolean = rpc.fetch("is_logged_in")

    /** 查询并缓存当前账号绑定的默认宿舍房间。 */
    public suspend fun defaultRoom(): RoomId = rpc.fetch("default_room")

    /** 查询校园卡余额，单位为元。 */
    public suspend fun balance(): BigDecimal = rpc.fetch("balance", null, DecimalSerializer)

    /**
     * 逐级检索电控位置目录，结果按 `id` 升序排列。
     *
     * @param parent 上级位置，依次为 `""`（区域）、`area`（楼栋）、`area-building`（楼层或单元）、
     * `area-building--level`（房间，其 [Location.id] 可用 [RoomId.parse] 解析）。
     */
    public suspend fun locations(parent: String = ""): List<Location> =
        rpc.fetch("locations", buildJsonObject { put("parent", parent) })

    /**
     * 查询房间的剩余电量，单位为度。
     *
     * @param room 目标房间，默认为 [defaultRoom]。
     */
    public suspend fun remainingEnergy(room: RoomId? = null): Double =
        rpc.fetch("remaining_energy", buildJsonObject { put("room", room?.value) })

    /**
     * 用校园卡支付密码从卡余额扣款，为 [room] 充值电费。
     *
     * 这是非幂等操作：协程被取消、超时或收到 [ZzuException.Decode]、[ZzuException.Crypto] 时服务端都可能已受理，
     * 须先查询 [balance] 再决定是否重试。
     *
     * @param amount 充值金额，单位为元，须为正整数。
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

    /** 查询校园卡充值的可选面额与网页收银台开关。 */
    public suspend fun rechargeConfig(): RechargeConfig = rpc.fetch("recharge_config")

    /**
     * 创建校园卡充值订单。
     *
     * @param amount 充值金额，单位为元，须为正整数。
     * @throws ZzuException.Remote 网页充值未开放时。
     */
    public suspend fun createRechargeOrder(amount: Int): RechargeOrder =
        rpc.fetch("create_recharge_order", buildJsonObject { put("amount", amount) })

    /** 查询 [order] 在学校收银台可用的支付渠道。 */
    public suspend fun paymentChannels(order: RechargeOrder): List<PaymentChannel> =
        rpc.fetch("payment_channels", buildJsonObject { put("order", order.wire) })

    /** 向收银台预下单，返回拉起第三方支付所需的动作。 */
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

/** 创建与 [cas] 共享认证会话与时限的校园卡客户端，不会发起网络请求；此后关闭 [cas] 不影响它。 */
public fun EcardClient(cas: CasClient): EcardClient =
    EcardClient(ZzuRpc(cas.transport.newEcardTransport()))

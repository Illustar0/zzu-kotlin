package com.illustar0.zzu.ecard

import com.illustar0.zzu.Decimal
import com.illustar0.zzu.internal.SecondsSerializer
import com.illustar0.zzu.internal.ZonedInstantSerializer
import com.illustar0.zzu.internal.ZzuJson
import com.illustar0.zzu.internal.nativeCheckoutUrl
import com.illustar0.zzu.internal.nativeRoomIdParse
import com.illustar0.zzu.internal.putValue
import dev.drewhamilton.poko.Poko
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 经过校验的宿舍房间号，格式为 `area-building--level-room`（四段均非空且不含 `-`，如 `"1-2--3-301"`）。
 *
 * @property value 规范化的房间号字符串。
 */
@JvmInline
@Serializable
public value class RoomId internal constructor(public val value: String) {
    public companion object {
        /**
         * 校验并解析房间号 [room]，只做本地计算，无需客户端或登录。
         *
         * @throws com.illustar0.zzu.ZzuException.InvalidInput [room] 不符合 `area-building--level-room` 格式时。
         */
        public fun parse(room: String): RoomId = RoomId(nativeRoomIdParse(room))
    }
}

/**
 * 电控位置目录的一项。
 *
 * @property id 位置编码；当父级为楼层（`area-building--level`）时可传给 [RoomId.parse] 解析为房间号。
 * @property name 位置显示名称。
 */
@Poko
@Serializable
public class Location(
    public val id: String,
    public val name: String,
)

/**
 * 宿舍电控账户详情快照。
 *
 * @property room 本次查询对应的规范宿舍房间号；调用未传房间时为当前账号绑定的默认房间。
 * @property roomName 宿舍房间完整位置名称；查询默认房间或传入房间命中已缓存的默认房间时返回，直接查询其他未缓存房间时为 `null`。
 * @property remainingEnergy 当前剩余电量，单位为度（kWh）。
 * @property unitPrice 电价单价，单位为元/度；服务端未返回时为 `null`。
 * @property electricityBalance 按金额计费房间的电费账户余额，单位为元；按电量计费的宿舍通常为 `null`。
 * @property cardBalance 查询时刻关联的校园卡账户余额快照，单位为元；服务端未返回时为 `null`。
 * @property status 电控表计运行状态描述，如 `"正常"`；服务端未返回时为 `null`。
 */
@Poko
@Serializable
@Suppress("LongParameterList") // 构造参数与 libzzu 的电控账户详情 JSON 字段一一对应。
public class EnergyAccount(
    public val room: RoomId,
    public val roomName: String? = null,
    public val remainingEnergy: Double,
    public val unitPrice: Decimal? = null,
    public val electricityBalance: Decimal? = null,
    public val cardBalance: Decimal? = null,
    public val status: String? = null,
)

/**
 * 在线校园码（动态付款码与通行码）载荷及自动刷新配置。
 *
 * [code] 为实时支付与门禁凭据，切勿写入日志；[toString] 已隐藏该字段。
 *
 * @property code 用于渲染二维码图像的动态码原始字符串。
 * @property balance 生成动态码时刻的校园卡账户余额，单位为元。
 * @property refreshInterval 服务端建议的动态码自动刷新间隔。
 */
@Poko
@Serializable
public class PaymentCode(
    public val code: String,
    public val balance: Decimal,
    @SerialName("refreshSeconds")
    @Serializable(with = SecondsSerializer::class)
    public val refreshInterval: Duration,
) {
    override fun toString(): String =
        "PaymentCode(balance=$balance, refreshInterval=$refreshInterval)"
}

/** 校园卡账单流水的交易类别筛选。 */
@Serializable
public enum class TransactionKind {
    /** 消费扣款，如食堂餐饮、商超购物与宿舍购电。 */
    @SerialName("consumption")
    CONSUMPTION,

    /** 充值入账，如微信、支付宝或银行卡转账充值。 */
    @SerialName("recharge")
    RECHARGE,

    /** 补助发放。 */
    @SerialName("subsidy")
    SUBSIDY,
}

/**
 * 校园卡账单流水的筛选条件（起止日期均含当日）。
 *
 * @property startDate 查询起始公历日期（含）。
 * @property endDate 查询截止公历日期（含），不得早于 [startDate]。
 * @property kind 交易类别筛选，`null` 表示查询全部类别（消费、充值与补助）。
 */
@Poko
@Serializable
public class TransactionQuery(
    public val startDate: LocalDate,
    public val endDate: LocalDate,
    public val kind: TransactionKind? = null,
) {
    public companion object {
        /** 校园卡账单流水分页查询的默认每页条数。 */
        public const val DEFAULT_PAGE_SIZE: Int = 30
    }
}

/**
 * 校园卡单笔交易账单记录。
 *
 * [time] 须用 [ZzuTimeZone][com.illustar0.zzu.ZzuTimeZone] 换算为当地时间。
 *
 * @property id 校园卡交易流水号。
 * @property time 交易发生时刻。
 * @property amount 带符号精确交易金额，单位为元；消费通常为负数，充值与补助通常为正数。
 * @property merchantName 交易商户或入账渠道名称。
 * @property summary 交易摘要说明，如购电房间描述或移动端交易备注。
 * @property isRefund 是否为退款记录。
 */
@Poko
@Serializable
public class Transaction(
    public val id: String,
    @Serializable(with = ZonedInstantSerializer::class)
    public val time: Instant,
    public val amount: Decimal,
    public val merchantName: String = "",
    public val summary: String = "",
    public val isRefund: Boolean = false,
)

/**
 * 校园卡充值面额与网页收银台配置。
 *
 * @property amounts 服务端预设的可选整元充值面额列表，单位为元。
 * @property isBrowserPaymentEnabled 服务端是否开放网页收银台充值。
 */
@Poko
@Serializable
public class RechargeConfig(
    public val amounts: List<Int>,
    public val isBrowserPaymentEnabled: Boolean,
)

/**
 * 已创建的校园卡网页充值订单。
 *
 * 可用 `Json.encodeToString(order)` 序列化保存并原样反序列化恢复，便于跳转外部收银台后在新进程中继续支付。
 * 序列化结果与 [checkoutUrl] 均包含支付凭据，切勿写入日志；[toString] 仅输出订单号与金额。
 *
 * @property id 学校收银台订单流水号。
 * @property amount 订单充值金额，单位为元。
 * @property checkoutUrl 未预选支付渠道的默认学校网页收银台 HTTPS 地址。
 */
@Serializable(with = RechargeOrderSerializer::class)
public class RechargeOrder internal constructor(internal val wire: JsonObject) {
    public val id: String = field("id").jsonPrimitive.content
    public val amount: Int = field("amount").jsonPrimitive.int
    public val checkoutUrl: String = field("checkoutUrl").jsonPrimitive.content

    private fun field(name: String): JsonElement =
        wire[name] ?: throw SerializationException("充值订单缺少 $name")

    /**
     * 生成预选指定支付渠道 [channel] 的学校网页收银台地址，只做本地计算，无需客户端或登录。
     *
     * @throws com.illustar0.zzu.ZzuException.InvalidInput [PaymentChannel.id] 为空字符串时。
     * @throws com.illustar0.zzu.ZzuException.Decode 订单参数序列化或收银台 URL 校验失败时。
     */
    public fun checkoutUrlFor(channel: PaymentChannel): String = nativeCheckoutUrl(
        buildJsonObject {
            put("order", wire)
            putValue("channel", channel)
        },
    )

    override fun equals(other: Any?): Boolean = other is RechargeOrder && wire == other.wire

    override fun hashCode(): Int = wire.hashCode()

    override fun toString(): String = "RechargeOrder(id=$id, amount=$amount)"
}

/** 订单的不透明字段须原样传回 libzzu，因此序列化形式就是其 JSON 对象本身。 */
internal object RechargeOrderSerializer : KSerializer<RechargeOrder> {
    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        SerialDescriptor("com.illustar0.zzu.ecard.RechargeOrder", JsonObject.serializer().descriptor)

    override fun deserialize(decoder: Decoder): RechargeOrder =
        RechargeOrder(JsonObject.serializer().deserialize(decoder))

    override fun serialize(encoder: Encoder, value: RechargeOrder) {
        JsonObject.serializer().serialize(encoder, value.wire)
    }
}

/**
 * 学校网页收银台支持的支付渠道（如支付宝、微信支付）。
 *
 * @property id 支付渠道编码。
 * @property name 支付渠道显示名称。
 * @property accountId 渠道关联的收款账户标识，部分渠道才有。
 */
@Poko
@Serializable
public class PaymentChannel(
    public val id: String,
    public val name: String,
    public val accountId: String? = null,
)

/** 向收银台预下单时上报的付款端设备平台。 */
@Serializable
public enum class PaymentDevice {
    /** Android 平台。 */
    @SerialName("android")
    ANDROID,

    /** iOS 平台。 */
    @SerialName("ios")
    IOS,
}

/**
 * 预下单时上报的付款端环境信息。
 *
 * @property device 付款端设备平台，默认为 [PaymentDevice.ANDROID]。
 * @property payerIp 付款端 IPv4 或 IPv6 地址；为 `null` 时按空字符串上报。
 */
@Poko
@Serializable
public class PaymentContext(
    public val device: PaymentDevice = PaymentDevice.ANDROID,
    public val payerIp: String? = null,
)

/**
 * 收银台预下单后返回的第三方支付拉起动作。
 *
 * 目标地址与表单内容含签名等支付凭据，[toString] 已剥离查询参数并隐藏表单值。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
public sealed interface PaymentAction {
    /** 不含用户凭据的第三方支付目标 HTTPS 地址。 */
    public val url: String

    /**
     * 通过 HTTP `GET` 跳转至第三方支付页面（如微信 H5 支付）。
     *
     * @property url 第三方支付目标 HTTPS 地址。
     * @property referrer 跳转时须附带的 HTTP `Referer` 请求头，不需要时为 `null`。
     */
    @Poko
    @Serializable
    @SerialName("redirect")
    public class Redirect(
        override val url: String,
        public val referrer: String? = null,
    ) : PaymentAction {
        override fun toString(): String = "Redirect(url=${url.substringBefore('?')})"
    }

    /**
     * 通过向 [url] 提交 HTML `POST` 表单进入第三方支付网关（如支付宝收银台）。
     *
     * @property url 表单提交目标 HTTPS 地址。
     * @property fields 按原始顺序排列的表单字段；可能包含同名字段，提交时须保留重复项与顺序。
     */
    @Poko
    @Serializable
    @SerialName("submit_form")
    public class SubmitForm(
        override val url: String,
        public val fields: List<FormField>,
    ) : PaymentAction {
        override fun toString(): String =
            "SubmitForm(url=${url.substringBefore('?')}, fieldCount=${fields.size})"
    }
}

/**
 * [PaymentAction.SubmitForm] 中的单个表单字段；[toString] 不输出 [value]。
 *
 * @property name 表单字段名。
 * @property value 表单字段值。
 */
@Poko
@Serializable
public class FormField(
    public val name: String,
    public val value: String,
) {
    override fun toString(): String = "FormField(name=$name)"
}

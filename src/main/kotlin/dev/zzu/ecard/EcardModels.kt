package dev.zzu.ecard

import dev.drewhamilton.poko.Poko
import dev.zzu.internal.ZzuJson
import dev.zzu.internal.nativeCheckoutUrl
import dev.zzu.internal.nativeRoomIdParse
import dev.zzu.internal.putValue
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

/** 经过校验的宿舍房间号，格式为 `area-building--level-room`。 */
@JvmInline
@Serializable
public value class RoomId internal constructor(public val value: String) {
    public companion object {
        /**
         * 校验并解析房间号，只做本地计算，无需客户端或登录。
         *
         * @throws dev.zzu.ZzuException.InvalidInput 格式非法时。
         */
        public fun parse(room: String): RoomId = RoomId(nativeRoomIdParse(room))
    }
}

/** 电控位置目录的一项。 */
@Poko
@Serializable
public class Location(
    public val id: String,
    public val name: String,
)

/**
 * 校园卡充值配置。
 *
 * @property amounts 可选面额，单位为元。
 * @property isBrowserPaymentEnabled 是否开放网页收银台充值。
 */
@Poko
@Serializable
public class RechargeConfig(
    public val amounts: List<Int>,
    public val isBrowserPaymentEnabled: Boolean,
)

/**
 * 校园卡充值订单。
 *
 * 用 `Json.encodeToString(order)` 可以保存，跳转收银台后在新进程恢复并继续支付。序列化结果与
 * [checkoutUrl] 都含支付凭据，不得写入日志，[toString] 只输出订单号与金额。
 *
 * @property id 订单号。
 * @property amount 充值金额，单位为元。
 * @property checkoutUrl 默认的学校收银台 HTTPS 地址。
 */
@Serializable(with = RechargeOrderSerializer::class)
public class RechargeOrder internal constructor(internal val wire: JsonObject) {
    public val id: String = field("id").jsonPrimitive.content
    public val amount: Int = field("amount").jsonPrimitive.int
    public val checkoutUrl: String = field("checkoutUrl").jsonPrimitive.content

    private fun field(name: String): JsonElement =
        wire[name] ?: throw SerializationException("充值订单缺少 $name")

    /**
     * 返回预选 [channel] 的学校收银台地址，只做本地计算，无需客户端或登录。
     *
     * @throws dev.zzu.ZzuException.InvalidInput 渠道无效时。
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
        SerialDescriptor("dev.zzu.ecard.RechargeOrder", JsonObject.serializer().descriptor)

    override fun deserialize(decoder: Decoder): RechargeOrder =
        RechargeOrder(JsonObject.serializer().deserialize(decoder))

    override fun serialize(encoder: Encoder, value: RechargeOrder) {
        JsonObject.serializer().serialize(encoder, value.wire)
    }
}

/**
 * 收银台支付渠道。
 *
 * @property accountId 渠道账户，部分渠道才有。
 */
@Poko
@Serializable
public class PaymentChannel(
    public val id: String,
    public val name: String,
    public val accountId: String? = null,
)

/** 付款端设备平台。 */
@Serializable
public enum class PaymentDevice {
    @SerialName("android")
    ANDROID,

    @SerialName("ios")
    IOS,
}

/**
 * 预下单时上报的付款端信息。
 *
 * @property payerIp IPv4 或 IPv6 地址，省略时按空字符串上报。
 */
@Poko
@Serializable
public class PaymentContext(
    public val device: PaymentDevice = PaymentDevice.ANDROID,
    public val payerIp: String? = null,
)

/** 拉起第三方支付所需的动作。地址含签名等凭据，[toString] 不输出查询参数与表单内容。 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
public sealed interface PaymentAction {
    /** 收银台地址。 */
    public val url: String

    /**
     * 跳转到 [url]，如微信 H5 支付。
     *
     * @property referrer 非空时必须作为 `Referer` 请求头携带。
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
     * 向 [url] 以 `POST` 提交表单，如支付宝网关。
     *
     * @property fields 表单字段，可能有同名项，须保留重复项与原始顺序。
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

/** [PaymentAction.SubmitForm] 的一个表单字段。 */
@Poko
@Serializable
public class FormField(
    public val name: String,
    public val value: String,
) {
    override fun toString(): String = "FormField(name=$name)"
}

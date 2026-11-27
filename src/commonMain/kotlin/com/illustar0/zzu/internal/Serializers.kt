package com.illustar0.zzu.internal

import com.illustar0.zzu.Decimal
import com.illustar0.zzu.ZzuTimeZone
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.offsetAt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** 金额在 ABI 上是十进制字符串，不经过浮点数。 */
internal object DecimalSerializer : KSerializer<Decimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.illustar0.zzu.Decimal", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Decimal = Decimal.parse(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: Decimal) {
        encoder.encodeString(value.toPlainString())
    }
}

/**
 * RFC 9557 时间；读取时偏移量已足以确定时刻，方括号内的时区名只是注解。
 *
 * 写出时必须带上时区名：`zzu_timetable_to_calendar` 用 jiff 解析，缺少它会被拒绝。
 */
internal object ZonedInstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.illustar0.zzu.ZonedInstant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant =
        Instant.parse(decoder.decodeString().substringBefore('['))

    override fun serialize(encoder: Encoder, value: Instant) {
        val offset = ZzuTimeZone.offsetAt(value)
        val text = value.format(DateTimeComponents.Formats.ISO_DATE_TIME_OFFSET, offset)
        encoder.encodeString("$text[${ZzuTimeZone.id}]")
    }
}

/** 星期在 ABI 上是 ISO 序号，`1` 为周一。 */
internal object IsoDayOfWeekSerializer : KSerializer<DayOfWeek> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.illustar0.zzu.IsoDayOfWeek", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): DayOfWeek = DayOfWeek(decoder.decodeInt())

    override fun serialize(encoder: Encoder, value: DayOfWeek) {
        encoder.encodeInt(value.isoDayNumber)
    }
}

internal object SecondsSerializer : KSerializer<Duration> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.illustar0.zzu.Seconds", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Duration = decoder.decodeLong().seconds

    override fun serialize(encoder: Encoder, value: Duration) {
        encoder.encodeLong(value.inWholeSeconds)
    }
}

/** 图像载荷在 ABI 上编码为标准 Base64（RFC 4648）字符串。 */
internal object Base64ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.illustar0.zzu.Base64ByteArray", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ByteArray =
        Base64.decode(decoder.decodeString().trim())

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(Base64.encode(value))
    }
}

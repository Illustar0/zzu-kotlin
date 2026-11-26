package com.illustar0.zzu.internal

import com.sun.jna.ptr.PointerByReference
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

internal actual fun nativeRoomIdParse(room: String): String {
    val input = room.encodeToByteArray()
    return computeText { LibZzu.zzu_room_id_parse(input, input.nativeSize, it) }
}

internal actual fun nativeCheckoutUrl(params: JsonObject): String {
    val input = params.toUtf8()
    return computeText { LibZzu.zzu_recharge_order_checkout_url_for(input, input.nativeSize, it) }
}

internal actual fun nativeTimetableToCalendar(params: JsonObject): String {
    val input = params.toUtf8()
    return computeText { LibZzu.zzu_timetable_to_calendar(input, input.nativeSize, it) }
}

/** 运行一个本地纯函数，其成功结果是 JSON 字符串。 */
private inline fun computeText(compute: (PointerByReference) -> Int): String {
    val outPayload = PointerByReference()
    val status = compute(outPayload)
    val payload = takeBytes(outPayload.value)
    if (status != ZZU_OK) throw zzuErrorOf(status, payload)
    return ZzuJson.decodeFromString(serializer<String>(), payload.decodeToString())
}

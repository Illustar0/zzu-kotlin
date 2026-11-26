@file:OptIn(ExperimentalForeignApi::class)

package com.illustar0.zzu.internal

import cnames.structs.ZzuBuffer
import com.illustar0.zzu.internal.cinterop.zzu_recharge_order_checkout_url_for
import com.illustar0.zzu.internal.cinterop.zzu_room_id_parse
import com.illustar0.zzu.internal.cinterop.zzu_timetable_to_calendar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

internal actual fun nativeRoomIdParse(room: String): String {
    val input = room.encodeToByteArray()
    return computeText { outPayload ->
        input.useUBytePointer { ptr, len -> zzu_room_id_parse(ptr, len, outPayload) }
    }
}

internal actual fun nativeCheckoutUrl(params: JsonObject): String {
    val input = params.toUtf8()
    return computeText { outPayload ->
        input.useUBytePointer { ptr, len -> zzu_recharge_order_checkout_url_for(ptr, len, outPayload) }
    }
}

internal actual fun nativeTimetableToCalendar(params: JsonObject): String {
    val input = params.toUtf8()
    return computeText { outPayload ->
        input.useUBytePointer { ptr, len -> zzu_timetable_to_calendar(ptr, len, outPayload) }
    }
}

private inline fun computeText(compute: (CPointer<CPointerVar<ZzuBuffer>>) -> Int): String = memScoped {
    ensureAbiCompatible()
    val outPayload = alloc<CPointerVar<ZzuBuffer>>()
    val status = compute(outPayload.ptr)
    val payload = takeBytes(outPayload.value)
    if (status != ZZU_OK) throw zzuErrorOf(status, payload)
    ZzuJson.decodeFromString(serializer<String>(), payload.decodeToString())
}

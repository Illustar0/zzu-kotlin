package dev.zzu

import dev.zzu.internal.CasTransport
import dev.zzu.internal.ZzuTransport
import kotlinx.serialization.json.JsonObject

/** 记录每次调用并回放预设结果的通道，用来验证方法名、参数与结果解码。 */
internal class FakeTransport(private val respond: (String) -> String = { "null" }) : CasTransport {
    val calls = mutableListOf<Pair<String, JsonObject?>>()
    var derivedTransports = 0
    var isClosed = false

    override suspend fun call(method: String, params: JsonObject?): ByteArray {
        calls += method to params
        return respond(method).encodeToByteArray()
    }

    override fun newEasTransport(): ZzuTransport = also { derivedTransports++ }

    override fun newEcardTransport(): ZzuTransport = also { derivedTransports++ }

    override fun close() {
        isClosed = true
    }
}

internal const val SCHEDULE_JSON = """{
    "scheduleGroupId": 1, "date": "2026-10-26", "weekday": 1,
    "startTime": "2026-10-26T08:00:00+08:00[Asia/Shanghai]",
    "endTime": "2026-10-26T09:50:00+08:00[Asia/Shanghai]",
    "realStartTime": "2026-10-26T08:00:00+08:00[Asia/Shanghai]",
    "realEndTime": "2026-10-26T09:50:00.5+08:00[Asia/Shanghai]",
    "teacherName": "教师", "teacherNameEn": null, "customPlace": null,
    "room": {
        "id": 7, "nameZh": "教室", "nameEn": null,
        "building": {"id": 8, "nameZh": "教学楼", "nameEn": null, "code": "B"},
        "campus": {"id": 9, "nameZh": "主校区", "nameEn": "Main", "code": "C"},
        "upstreamOnly": true
    },
    "startUnit": 1, "endUnit": 2, "state": "NORMAL", "weekIndex": 3,
    "lessonType": "THEORY", "periods": 2, "newFieldFromUpstream": {"a": 1}
}"""

internal const val COURSE_JSON = """{
    "id": 2, "code": "CS101", "nameZh": "程序设计", "nameEn": null, "credits": 2.5,
    "periodInfo": {"total": 32, "weeks": 16, "periodsPerWeek": 2}
}"""

internal const val TEACHING_WEEK_JSON =
    """{"weekIndex": 3, "lessons": [{"course": $COURSE_JSON, "schedule": $SCHEDULE_JSON}]}"""

internal const val ORDER_JSON = """{
    "id": "school-order", "amount": 10, "checkoutUrl": "https://example.test/pay?secret=abc",
    "payment": {
        "orderInfo": {
            "partnerid": "partner", "journo": "school-order", "merchantno": "merchant",
            "channelno": "103", "txamt": "1000"
        },
        "callPaywayid": "", "callAccountid": "",
        "extend": {"accessToken": "test-payment-secret"}
    }
}"""

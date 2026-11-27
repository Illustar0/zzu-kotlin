package com.illustar0.zzu

import com.illustar0.zzu.internal.CasTransport
import com.illustar0.zzu.internal.ZzuTransport
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

    override fun newMailTransport(): ZzuTransport = also { derivedTransports++ }

    override fun close() {
        isClosed = true
    }
}

internal const val SEMESTER_JSON = """{
    "id": 194, "code": "2026-2027-1", "nameZh": "2026-2027学年第一学期", "nameEn": null,
    "schoolYear": "2026-2027", "season": "autumn",
    "startDate": "2026-09-07", "endDate": "2027-01-17",
    "weekStartOnSunday": false, "countInTerm": true,
    "weekIndices": [1, 2, 3, 4, 5, 6, 7, 8], "upstreamOnly": "ignored"
}"""

internal const val TIMETABLE_JSON = """{
    "semester": $SEMESTER_JSON,
    "units": [
        {
            "index": 1, "nameZh": "1", "nameEn": "1", "dayPart": "morning",
            "segmentIndex": 1, "startTime": "08:00:00", "endTime": "08:45:00"
        },
        {
            "index": 2, "nameZh": "2", "nameEn": null, "dayPart": "morning",
            "segmentIndex": 1, "startTime": "08:55:00", "endTime": "09:40:00"
        }
    ],
    "classes": [
        {
            "id": 1001, "code": "CS101-01", "origin": "regular",
            "course": {
                "id": 2, "code": "CS101", "nameZh": "程序设计", "nameEn": null, "credits": 2.5,
                "periodInfo": {"total": 32, "weeks": 16, "periodsPerWeek": 2, "theory": 24, "experiment": 8}
            },
            "courseType": {"id": 10, "nameZh": "专业必修", "nameEn": null, "code": "MAJOR"},
            "teachers": [{"nameZh": "教师甲", "nameEn": null}],
            "studentCount": 60,
            "scheduleState": "published",
            "scheduleText": "周一 1-2 节",
            "upstreamOnly": true
        },
        {
            "id": 1002, "code": "EXP201-01", "origin": "standalone_experiment",
            "course": {
                "id": 3, "code": "EXP201", "nameZh": "物理实验", "nameEn": null, "credits": null,
                "periodInfo": {"total": 16, "experiment": 16}
            },
            "scheduleState": "unpublished"
        }
    ],
    "sessions": [
        {
            "classId": 1001, "scheduleGroupId": 1, "weekIndex": 8,
            "date": "2026-10-26", "originalDate": null, "weekday": 1,
            "startTime": "2026-10-26T08:00:00+08:00[Asia/Shanghai]",
            "endTime": "2026-10-26T09:50:00.5+08:00[Asia/Shanghai]",
            "startUnit": 1, "endUnit": 2,
            "startUnitNameZh": "1", "endUnitNameZh": "2",
            "teachers": [{"id": 11, "personId": 22, "nameZh": "教师甲", "nameEn": null}],
            "room": {
                "id": 7, "nameZh": "南101", "nameEn": null,
                "building": {"id": 8, "nameZh": "综合楼", "nameEn": null, "code": "B"},
                "campus": {"id": 9, "nameZh": "主校区", "nameEn": "Main", "code": "C"},
                "seatNumber": null,
                "upstreamOnly": true
            },
            "customPlace": null,
            "kind": "theory", "state": "completed", "periods": 2,
            "experimentProject": null,
            "newFieldFromUpstream": {"a": 1}
        }
    ]
}"""

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

internal const val TRANSCRIPT_JSON = """{
    "gpa": 3.72,
    "rank": {"rank": 5, "total": 120},
    "grades": [
        {
            "semester": {
                "id": 194, "code": "2026-2027-1", "nameZh": "秋季", "nameEn": null,
                "schoolYear": "2026-2027", "season": "autumn",
                "startDate": "2026-09-07", "endDate": "2027-01-17"
            },
            "courseId": 10, "courseCode": "CS101", "courseNameZh": "程序设计",
            "studyType": "required", "credits": 3.0, "finalScore": "92", "gradePoint": 4.0,
            "isPassed": true, "isPublished": true, "isRetake": true, "state": "normal",
            "subGrades": [{
                "kind": {"id": 1, "nameZh": "平时成绩", "code": "USUAL"},
                "score": "95", "isPublished": true,
                "items": [{"name": "实验报告", "score": "95"}]
            }]
        },
        {
            "semester": {
                "id": 194, "code": "2026-2027-1", "nameZh": "秋季", "nameEn": null,
                "schoolYear": "2026-2027", "season": "autumn",
                "startDate": "2026-09-07", "endDate": "2027-01-17"
            },
            "courseCode": "CS102", "courseNameZh": "数据结构",
            "studyType": "elective", "credits": 4.0, "finalScore": null, "gradePoint": null,
            "isPassed": true, "isPublished": false, "isRetake": false, "state": "evaluation_required"
        },
        {
            "semester": {
                "id": 190, "code": "2025-2026-1", "nameZh": "秋季", "nameEn": null,
                "schoolYear": "2025-2026", "season": "autumn",
                "startDate": "2025-09-01", "endDate": "2026-01-15"
            },
            "courseCode": "CS101", "courseNameZh": "程序设计",
            "credits": 2.5, "finalScore": "61", "gradePoint": 1.2,
            "isPassed": true, "isPublished": true, "isRetake": false, "state": "deferred_pending"
        }
    ]
}"""

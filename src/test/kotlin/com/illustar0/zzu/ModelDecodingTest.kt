package com.illustar0.zzu

import com.illustar0.zzu.cas.PersonalInfo
import com.illustar0.zzu.cas.Tokens
import com.illustar0.zzu.eas.Lesson
import com.illustar0.zzu.eas.Semester
import com.illustar0.zzu.eas.SemesterId
import com.illustar0.zzu.eas.TeachingWeek
import com.illustar0.zzu.eas.day
import com.illustar0.zzu.eas.grid
import com.illustar0.zzu.eas.unit
import com.illustar0.zzu.ecard.FormField
import com.illustar0.zzu.ecard.PaymentAction
import com.illustar0.zzu.ecard.RechargeOrder
import com.illustar0.zzu.internal.DecimalSerializer
import com.illustar0.zzu.internal.ZzuJson
import com.illustar0.zzu.network.OnlineDevice
import com.illustar0.zzu.network.SessionId
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime

class ModelDecodingTest {
    @Test
    fun scheduleTimesKeepTheirInstantAndFractionalSeconds() {
        val week = ZzuJson.decodeFromString<TeachingWeek>(TEACHING_WEEK_JSON)

        val schedule = week.lessons.single().schedule

        assertEquals(DayOfWeek.MONDAY, schedule.weekday)
        assertEquals(Instant.parse("2026-10-26T00:00:00Z"), schedule.startTime)
        assertEquals(Instant.parse("2026-10-26T01:50:00.5Z"), schedule.realEndTime)
        assertEquals(
            LocalDateTime(2026, 10, 26, 8, 0),
            schedule.startTime.toLocalDateTime(ZzuTimeZone),
        )
    }

    @Test
    fun zonedTimesEncodeWithTheTimeZoneJiffRequires() {
        val week = ZzuJson.decodeFromString<TeachingWeek>(TEACHING_WEEK_JSON)

        val encoded = ZzuJson.encodeToString(week)

        assertContains(encoded, "2026-10-26T08:00:00+08:00[Asia/Shanghai]")
        assertEquals(week, ZzuJson.decodeFromString(encoded))
    }

    @Test
    fun semesterNamesFollowKotlinConventionsOverTheWireForm() {
        val semester = ZzuJson.decodeFromString<Semester>(
            """{"id": 42, "code": "2026-1", "nameZh": "秋季", "nameEn": null,
                "schoolYear": "2026-2027", "startDate": "2026-09-01", "endDate": "2027-01-15",
                "weekStartOnSunday": false, "countInTerm": true, "season": "AUTUMN",
                "weekIndices": [1, 2, 3], "upstreamOnly": "ignored"}""",
        )

        assertEquals(SemesterId(42), semester.id)
        assertFalse(semester.startsWeekOnSunday)
        assertEquals(listOf(1, 2, 3), semester.weekIndices)
    }

    @Test
    fun onlineDeviceConvertsSecondsAndDefaultsTheHostName() {
        val device = ZzuJson.decodeFromString<OnlineDevice>(
            """{"brasid": "1", "downFlow": "1024", "ip": "10.1.2.3",
                "loginTime": "2026-10-31T08:30:45+08:00[Asia/Shanghai]", "mac": "001122334455",
                "sessionId": "sid-1", "terminalType": "PC", "upFlow": "256",
                "useTime": 3661, "userId": 42}""",
        )

        assertEquals(3661.seconds, device.onlineDuration)
        assertEquals(SessionId("sid-1"), device.sessionId)
        assertEquals("", device.hostName)
        assertEquals("1", device.brasId)
    }

    @Test
    fun personalInfoDecodesAuthxAttributesAndRedactsSecrets() {
        val info = ZzuJson.decodeFromString<PersonalInfo>(
            """{"uid": "secret-uid", "name": "secret-name", "identityTypeName": "本科生",
                "identityTypeId": "1", "organizationName": "学院", "organizationId": "2",
                "upstreamOnly": true}""",
        )

        assertEquals("secret-uid", info.uid)
        assertEquals("secret-name", info.name)
        assertEquals("本科生", info.identityTypeName)
        assertEquals("1", info.identityTypeId)
        assertEquals("学院", info.organizationName)
        assertEquals("2", info.organizationId)
        assertFalse("secret" in info.toString(), "泄露于 $info")
    }

    @Test
    fun moneyStaysExactAndKeepsItsScale() {
        val amount = ZzuJson.decodeFromString(DecimalSerializer, "\"90071992547409.9300\"")

        assertEquals(BigDecimal("90071992547409.9300"), amount)
    }

    @Test
    fun paymentFormKeepsDuplicateFieldsInOrder() {
        val action = ZzuJson.decodeFromString<PaymentAction>(
            """{"kind": "submit_form", "url": "https://pay.test/gateway?sign=s",
                "fields": [{"name": "a", "value": "1"}, {"name": "a", "value": "2"}]}""",
        )

        val form = assertIs<PaymentAction.SubmitForm>(action)
        assertEquals(listOf(FormField("a", "1"), FormField("a", "2")), form.fields)
    }

    @Test
    fun paymentRedirectKeepsItsReferrer() {
        val action = ZzuJson.decodeFromString<PaymentAction>(
            """{"kind": "redirect", "url": "https://pay.test/?id=1", "referrer": "https://r.test/"}""",
        )

        assertEquals("https://r.test/", assertIs<PaymentAction.Redirect>(action).referrer)
    }

    @Test
    fun orderSurvivesPersistenceWithItsOpaqueFields() {
        val order = ZzuJson.decodeFromString<RechargeOrder>(ORDER_JSON)

        val restored = ZzuJson.decodeFromString<RechargeOrder>(ZzuJson.encodeToString(order))

        assertEquals(order, restored)
        assertEquals("school-order", restored.id)
        assertEquals(10, restored.amount)
    }

    @Test
    fun secretsStayOutOfToString() {
        val order = ZzuJson.decodeFromString<RechargeOrder>(ORDER_JSON)
        val redirect = PaymentAction.Redirect("https://pay.test/path?prepay_id=secret")
        val form = PaymentAction.SubmitForm(
            "https://pay.test/?sign=secret",
            listOf(FormField("sign", "secret")),
        )
        val request = RequestContext("GET", "https://a.test", responseBody = "secret")
        val tokens = Tokens("secret", "secret")

        listOf(order, redirect, form, form.fields, request, tokens).forEach { value ->
            assertFalse("secret" in value.toString(), "泄露于 $value")
        }
    }

    @Test
    fun weekLookupReturnsTheLastOverlappingLesson() {
        val first = lessonAt(startUnit = 1, endUnit = 2)
        val second = lessonAt(startUnit = 2, endUnit = 3)
        val week = TeachingWeek(3, listOf(first, second))

        assertSame(first, week[DayOfWeek.MONDAY, 1])
        assertSame(second, week[DayOfWeek.MONDAY, 2])
        assertNull(week[DayOfWeek.MONDAY, 4])
        assertNull(week[DayOfWeek.TUESDAY, 1])
        assertFailsWith<IllegalArgumentException> { week[DayOfWeek.MONDAY, 11] }
    }

    @Test
    fun gridIsSevenDaysOfTenUnits() {
        val lesson = lessonAt(startUnit = 1, endUnit = 2)
        val week = TeachingWeek(3, listOf(lesson))

        val grid = week.grid()

        assertEquals(7, grid.size)
        assertEquals(List(7) { 10 }, grid.map { it.size })
        assertSame(lesson, grid[0][1])
        assertEquals(week.day(DayOfWeek.MONDAY), grid[0])
        assertSame(lesson, week.unit(2)[0])
    }

    private fun lessonAt(startUnit: Int, endUnit: Int): Lesson {
        val json = TEACHING_WEEK_JSON
            .replace("\"startUnit\": 1", "\"startUnit\": $startUnit")
            .replace("\"endUnit\": 2", "\"endUnit\": $endUnit")
        return ZzuJson.decodeFromString<TeachingWeek>(json).lessons.single()
    }

    private fun assertContains(text: String, part: String) {
        assertEquals(true, part in text, "$text 不含 $part")
    }
}

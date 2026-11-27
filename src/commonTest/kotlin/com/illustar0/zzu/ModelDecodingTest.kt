package com.illustar0.zzu

import com.illustar0.zzu.cas.DevicePage
import com.illustar0.zzu.cas.LoginDevice
import com.illustar0.zzu.cas.PersonalInfo
import com.illustar0.zzu.cas.Portrait
import com.illustar0.zzu.cas.Tokens
import com.illustar0.zzu.eas.ClassOrigin
import com.illustar0.zzu.eas.ClassSession
import com.illustar0.zzu.eas.ClassroomCatalog
import com.illustar0.zzu.eas.ClassroomPage
import com.illustar0.zzu.eas.CompletionStatus
import com.illustar0.zzu.eas.CourseUnit
import com.illustar0.zzu.eas.DayPart
import com.illustar0.zzu.eas.Exam
import com.illustar0.zzu.eas.ExperimentProject
import com.illustar0.zzu.eas.GradeState
import com.illustar0.zzu.eas.ProgramCompletion
import com.illustar0.zzu.eas.RoomActivityType
import com.illustar0.zzu.eas.ScheduleState
import com.illustar0.zzu.eas.Semester
import com.illustar0.zzu.eas.SemesterId
import com.illustar0.zzu.eas.SemesterSeason
import com.illustar0.zzu.eas.SessionKind
import com.illustar0.zzu.eas.SessionState
import com.illustar0.zzu.eas.StudyType
import com.illustar0.zzu.eas.Timetable
import com.illustar0.zzu.eas.Transcript
import com.illustar0.zzu.eas.locateDate
import com.illustar0.zzu.ecard.EnergyAccount
import com.illustar0.zzu.ecard.FormField
import com.illustar0.zzu.ecard.PaymentAction
import com.illustar0.zzu.ecard.PaymentCode
import com.illustar0.zzu.ecard.RechargeOrder
import com.illustar0.zzu.ecard.RoomId
import com.illustar0.zzu.ecard.Transaction
import com.illustar0.zzu.internal.DecimalSerializer
import com.illustar0.zzu.internal.ZzuJson
import com.illustar0.zzu.mail.MailMessagePage
import com.illustar0.zzu.network.OnlineDevice
import com.illustar0.zzu.network.SessionId
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toLocalDateTime

class ModelDecodingTest {
    @Test
    fun sessionTimesKeepTheirInstantAndFractionalSeconds() {
        val timetable = ZzuJson.decodeFromString<Timetable>(TIMETABLE_JSON)

        val session = timetable.sessions.single()

        assertEquals(DayOfWeek.MONDAY, session.weekday)
        assertEquals(Instant.parse("2026-10-26T00:00:00Z"), session.startTime)
        assertEquals(Instant.parse("2026-10-26T01:50:00.5Z"), session.endTime)
        assertEquals(
            LocalDateTime(2026, 10, 26, 8, 0),
            session.startTime.toLocalDateTime(ZzuTimeZone),
        )
        assertEquals(SessionKind.THEORY, session.kind)
        assertEquals("理论", session.kind.labelZh)
        assertEquals(SessionState.COMPLETED, session.state)
        assertEquals("主校区 综合楼 南101", session.displayPlace)
    }

    @Test
    fun zonedTimesEncodeWithTheTimeZoneJiffRequires() {
        val timetable = ZzuJson.decodeFromString<Timetable>(TIMETABLE_JSON)

        val encoded = ZzuJson.encodeToString(timetable)

        assertContains(encoded, "2026-10-26T08:00:00+08:00[Asia/Shanghai]")
        assertEquals(timetable, ZzuJson.decodeFromString(encoded))
    }

    @Test
    fun courseUnitsLookupByIndexAndAcceptShortOrFractionalTimes() {
        val timetable = ZzuJson.decodeFromString<Timetable>(TIMETABLE_JSON)
        val firstUnit = timetable.unit(1)

        assertEquals(DayPart.MORNING, firstUnit?.dayPart)
        assertEquals(LocalTime(8, 0), firstUnit?.startTime)
        assertEquals(LocalTime(8, 45), firstUnit?.endTime)
        assertNull(timetable.unit(99))

        val customUnit = ZzuJson.decodeFromString<CourseUnit>(
            """{"index": 5, "nameZh": "第+1节", "dayPart": "noon", "segmentIndex": 3,
                "startTime": "12:10", "endTime": "12:55:00.5"}""",
        )
        assertEquals(DayPart.NOON, customUnit.dayPart)
        assertEquals(LocalTime(12, 10), customUnit.startTime)
        assertEquals(LocalTime(12, 55, 0, 500_000_000), customUnit.endTime)
        assertEquals(customUnit, ZzuJson.decodeFromString(ZzuJson.encodeToString(customUnit)))
    }

    @Test
    fun semesterNamesFollowKotlinConventionsAndSupportOfflineWeekMath() {
        val semester = ZzuJson.decodeFromString<Semester>(
            """{"id": 194, "code": "2026-2027-1", "nameZh": "秋季", "nameEn": null,
                "schoolYear": "2026-2027", "startDate": "2026-09-07", "endDate": "2027-01-17",
                "weekStartOnSunday": false, "countInTerm": true, "season": "autumn",
                "weekIndices": [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19],
                "upstreamOnly": "ignored"}""",
        )

        assertEquals(SemesterId(194), semester.id)
        assertEquals(SemesterSeason.AUTUMN, semester.season)
        assertFalse(semester.startsWeekOnSunday)
        assertNull(semester.weekOf(LocalDate(2026, 9, 6)))
        assertEquals(1, semester.weekOf(LocalDate(2026, 9, 7)))
        assertEquals(18, semester.weekOf(LocalDate(2027, 1, 5)))
        assertEquals(19, semester.weekOf(LocalDate(2027, 1, 17)))
        assertNull(semester.weekOf(LocalDate(2027, 1, 18)))
        assertEquals(
            LocalDate(2026, 11, 23)..LocalDate(2026, 11, 29),
            semester.weekDateRange(12),
        )
        assertNull(semester.weekDateRange(20))

        val sundayStart = Semester(
            id = SemesterId(50),
            code = "1997-2",
            nameZh = "春季",
            schoolYear = "1996-1997",
            season = SemesterSeason("intersession"),
            startDate = LocalDate(1997, 2, 1),
            endDate = LocalDate(1997, 2, 15),
            startsWeekOnSunday = true,
            isCountedInTerm = false,
            weekIndices = listOf(1, 2, 3),
        )
        assertEquals(1, sundayStart.weekOf(LocalDate(1997, 2, 1)))
        assertEquals(2, sundayStart.weekOf(LocalDate(1997, 2, 2)))

        val nonTermOverlap = Semester(
            id = SemesterId(200),
            code = "summer",
            nameZh = "短学期",
            schoolYear = "2026-2027",
            season = SemesterSeason.SUMMER,
            startDate = LocalDate(2026, 9, 7),
            endDate = LocalDate(2026, 9, 20),
            startsWeekOnSunday = false,
            isCountedInTerm = false,
            weekIndices = listOf(1, 2),
        )
        val located = listOf(nonTermOverlap, semester).locateDate(LocalDate(2026, 9, 8))
        assertEquals(semester to 1, located)
        assertNull(listOf(nonTermOverlap, semester).locateDate(LocalDate(2027, 2, 1)))
    }

    @Test
    fun onlineDeviceConvertsSecondsAndDefaultsTheHostName() {
        val device = ZzuJson.decodeFromString<OnlineDevice>(
            """{"brasId": "1", "downFlow": "1024", "ip": "10.1.2.3",
                "loginTime": "2026-10-31T08:30:45+08:00[Asia/Shanghai]", "mac": "001122334455",
                "sessionId": "sid-1", "terminalType": "PC", "upFlow": "256",
                "useTime": 3661, "userId": 42}""",
        )

        assertEquals(3661.seconds, device.onlineDuration)
        assertEquals(SessionId("sid-1"), device.sessionId)
        assertEquals("", device.hostName)
        assertEquals("1", device.brasId)
        val encoded = ZzuJson.encodeToString(device)
        assertContains(encoded, "\"brasId\":\"1\"")
        assertEquals(device, ZzuJson.decodeFromString(encoded))
    }

    @Test
    fun mailMessagePageDecodesTimestampsFlagsAndPagination() {
        val page = ZzuJson.decodeFromString<MailMessagePage>(
            """{"items": [{
                "id": "m-1", "from": "通知 <notice@zzu.edu.cn>", "to": "student@gs.zzu.edu.cn",
                "subject": "选课通知", "summary": "请按时选课",
                "sentTime": null, "receivedTime": "2026-11-27T10:20:00+08:00[Asia/Shanghai]",
                "size": 4096, "isRead": false, "hasAttachment": true, "isFlagged": false
            }], "page": 1, "pageSize": 20, "totalItems": 25, "totalPages": 2}""",
        )

        assertEquals(true, page.hasNext)
        val message = page.items.single()
        assertEquals("m-1", message.id)
        assertEquals("选课通知", message.subject)
        assertNull(message.sentTime)
        assertEquals(
            LocalDateTime(2026, 11, 27, 10, 20),
            message.receivedTime.toLocalDateTime(ZzuTimeZone),
        )
        assertEquals(4096L, message.size)
        assertFalse(message.isRead)
        assertEquals(true, message.hasAttachment)
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
    fun devicePageDecodesHistoryAndRedactsDeviceCredentials() {
        val page = ZzuJson.decodeFromString<DevicePage>(
            """{"items": [
                {
                    "id": "rec-1", "deviceId": "secret-device-id", "isTrusted": true,
                    "deviceType": "MOBILE", "os": "ANDROID", "browser": null,
                    "userAgent": "secret-ua", "usedCount": 5,
                    "firstUsedTime": "2026-10-01T09:00:00+08:00[Asia/Shanghai]",
                    "lastUsedTime": "2026-11-27T12:30:00+08:00[Asia/Shanghai]",
                    "previousUsedTime": null, "upstreamOnly": 1
                },
                {
                    "id": "web-1", "deviceId": null, "isTrusted": false,
                    "deviceType": "PC", "os": "OTHER", "browser": "UNKNOWN",
                    "userAgent": null, "usedCount": 0,
                    "firstUsedTime": null, "lastUsedTime": null, "previousUsedTime": null
                }
            ], "page": 1, "pageSize": 20, "totalItems": 25, "totalPages": 2}""",
        )

        assertEquals(true, page.hasNext)
        val (device, webSession) = page.items
        assertEquals("rec-1", device.id)
        assertEquals("secret-device-id", device.deviceId)
        assertEquals(true, device.isTrusted)
        assertEquals("MOBILE", device.deviceType)
        assertEquals(5, device.usedCount)
        assertEquals(
            LocalDateTime(2026, 11, 27, 12, 30),
            device.lastUsedTime?.toLocalDateTime(ZzuTimeZone),
        )
        assertNull(device.previousUsedTime)
        assertFalse("secret" in device.toString(), "泄露于 $device")

        assertEquals(
            LoginDevice(
                id = "web-1",
                isTrusted = false,
                deviceType = "PC",
                os = "OTHER",
                browser = "UNKNOWN",
                usedCount = 0,
            ),
            webSession,
        )
        assertNull(webSession.deviceId)
        assertNull(webSession.firstUsedTime)
        assertNull(webSession.lastUsedTime)
        assertEquals(page, ZzuJson.decodeFromString<DevicePage>(ZzuJson.encodeToString(page)))
    }

    @Test
    fun portraitDecodesBase64BytesAndOmitsRawBytesFromToString() {
        val portrait = ZzuJson.decodeFromString<Portrait>(
            """{"mediaType": "image/jpeg", "data": "c2VjcmV0", "upstreamOnly": true}""",
        )

        assertEquals("image/jpeg", portrait.mediaType)
        assertEquals("secret", portrait.bytes.decodeToString())
        val roundTripped = ZzuJson.decodeFromString<Portrait>(ZzuJson.encodeToString(portrait))
        assertEquals(portrait, roundTripped)
        assertEquals(portrait.hashCode(), roundTripped.hashCode())
        assertEquals("Portrait(mediaType=image/jpeg, byteCount=6)", portrait.toString())
    }

    @Test
    fun moneyStaysExactAndKeepsItsScale() {
        val amount = ZzuJson.decodeFromString(DecimalSerializer, "\"90071992547409.9300\"")

        assertEquals(Decimal.parse("90071992547409.9300"), amount)
        assertEquals(4, amount.scale)
        assertEquals("90071992547409.9300", amount.toPlainString())
    }

    @Test
    fun transactionDecodesSignedDecimalAmountAndTimestamp() {
        val transaction = ZzuJson.decodeFromString<Transaction>(
            """{"id": "tx-1", "time": "2026-11-27T12:15:30+08:00[Asia/Shanghai]",
                "amount": "-12.50", "merchantName": "主校区电控", "summary": "购电",
                "isRefund": false, "upstreamOnly": 1}""",
        )

        assertEquals("tx-1", transaction.id)
        assertEquals(Decimal.parse("-12.50"), transaction.amount)
        assertEquals(
            LocalDateTime(2026, 11, 27, 12, 15, 30),
            transaction.time.toLocalDateTime(ZzuTimeZone),
        )
        assertEquals("主校区电控", transaction.merchantName)
        assertFalse(transaction.isRefund)
    }

    @Test
    fun energyAccountDecodesRoomIdAndOptionalDecimals() {
        val account = ZzuJson.decodeFromString<EnergyAccount>(
            """{"room": "1-2--3-301", "roomName": "主校区松园2号楼301", "remainingEnergy": 88.6,
                "unitPrice": "0.56", "electricityBalance": null, "cardBalance": "35.20",
                "status": "正常", "upstreamOnly": true}""",
        )

        assertEquals(RoomId("1-2--3-301"), account.room)
        assertEquals("主校区松园2号楼301", account.roomName)
        assertEquals(88.6, account.remainingEnergy)
        assertEquals(Decimal.parse("0.56"), account.unitPrice)
        assertNull(account.electricityBalance)
        assertEquals(Decimal.parse("35.20"), account.cardBalance)
        assertEquals("正常", account.status)
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
        val paymentCode = PaymentCode("secret", Decimal.parse("10.00"), 60.seconds)

        listOf(order, redirect, form, form.fields, request, tokens, paymentCode).forEach { value ->
            assertFalse("secret" in value.toString(), "泄露于 $value")
        }
    }

    @Test
    fun timetableSupportsLocalQueriesAndWeekViews() {
        val base = ZzuJson.decodeFromString<Timetable>(TIMETABLE_JSON)
        val timetable = sampleConflictTimetable(base)

        assertEquals(ClassOrigin.REGULAR, timetable.teachingClass(1001)?.origin)
        assertEquals(ScheduleState.UNPUBLISHED, timetable.teachingClass(1002)?.scheduleState)
        assertEquals(listOf(1002L), base.unscheduledClasses().map { it.id })
        assertEquals(2.5, timetable.totalCredits())
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 10), timetable.weekIndices())
        assertEquals(8, timetable.weekOf(LocalDate(2026, 10, 26)))
        assertEquals("custom_kind", timetable.sessions.last().kind.labelZh)

        val week = timetable.week(8)
        assertEquals(LocalDate(2026, 10, 26)..LocalDate(2026, 11, 1), week.dateRange())
        assertEquals(3, week.maxUnit())
        assertEquals(2, week.day(DayOfWeek.MONDAY).size)
        assertEquals(2, week[DayOfWeek.MONDAY, 2].size)
        assertEquals("物理实验 - 分光计测量", week[DayOfWeek.MONDAY, 3].single().displayTitle)
        assertEquals("物理楼 201", week[DayOfWeek.MONDAY, 3].single().displayPlace)
        assertFailsWith<IllegalArgumentException> { timetable.week(0) }
        assertFailsWith<IllegalArgumentException> { week[DayOfWeek.MONDAY, 0] }
    }

    @Test
    fun timetableDetectsConflictsAndFiltersByTimeRange() {
        val base = ZzuJson.decodeFromString<Timetable>(TIMETABLE_JSON)
        val timetable = sampleConflictTimetable(base)
        val extraWeekSession = timetable.sessions.last()

        val conflicts = timetable.week(8).conflicts()
        assertEquals(1, conflicts.size)
        assertEquals(Instant.parse("2026-10-26T00:30:00Z"), conflicts.single().startTime)
        assertEquals(Instant.parse("2026-10-26T01:50:00.5Z"), conflicts.single().endTime)
        assertEquals(2, conflicts.single().sessions.size)

        assertEquals(2, timetable.sessionsOn(LocalDate(2026, 10, 26)).size)
        assertEquals(
            1,
            timetable.sessionsBetween(
                Instant.parse("2026-10-26T00:00:00Z"),
                Instant.parse("2026-10-26T00:30:00Z"),
            ).size,
        )
        assertFailsWith<IllegalArgumentException> {
            val instant = Instant.parse("2026-10-26T00:00:00Z")
            timetable.sessionsBetween(instant, instant)
        }
        assertEquals(2, timetable.currentSessions(Instant.parse("2026-10-26T01:00:00Z")).size)
        assertSame(
            extraWeekSession,
            timetable.nextSession(Instant.parse("2026-10-26T01:00:00Z"))?.session,
        )
    }

    @Test
    fun classroomCatalogAndOccupancyDecodeAndCheckFreeIntervals() {
        val catalog = ZzuJson.decodeFromString<ClassroomCatalog>(
            """{"campuses": [{"id": 1, "code": "MAIN", "nameZh": "主校区", "enabled": true}],
                "buildings": [{"id": 10, "code": "B1", "nameZh": "南核心", "campusId": 1, "enabled": true}],
                "roomTypes": [{"id": 2, "code": "NORMAL", "nameZh": "普通教室", "enabled": false}],
                "floors": [1, 2],
                "units": [{"index": 9, "nameZh": "9", "dayPart": "evening", "segmentIndex": 5,
                    "startTime": "19:00:00", "endTime": "19:45:00"}]}""",
        )
        assertEquals(true, catalog.campuses.single().isEnabled)
        assertEquals(1L, catalog.buildings.single().campusId)
        assertFalse(catalog.roomTypes.single().isEnabled)
        assertEquals(DayPart.EVENING, catalog.units.single().dayPart)

        val page = ZzuJson.decodeFromString<ClassroomPage>(
            """{"items": [{
                "id": 99, "code": "N101", "nameZh": "南101", "buildingId": 10, "campusNameZh": "主校区",
                "occupations": [{
                    "activityId": 5, "activityType": "room_borrow", "activityName": "讲座",
                    "date": "2026-11-24",
                    "startTime": "2026-11-24T10:00:00+08:00[Asia/Shanghai]",
                    "endTime": "2026-11-24T12:00:00+08:00[Asia/Shanghai]",
                    "teacherName": "教师"
                }]
            }], "page": 1, "pageSize": 44, "totalItems": 1, "totalPages": 1}""",
        )
        assertFalse(page.hasNext)
        val room = page.items.single()
        assertEquals(RoomActivityType.ROOM_BORROW, room.occupations.single().activityType)
        assertEquals(
            listOf(
                RoomActivityType.LESSON,
                RoomActivityType.EXPERIMENT,
                RoomActivityType.EXAM,
                RoomActivityType.SCHEDULE_CHANGE,
                RoomActivityType("custom_event"),
            ),
            ZzuJson.decodeFromString<List<RoomActivityType>>(
                """["lesson", "experiment", "exam", "schedule_change", "custom_event"]""",
            ),
        )
        assertEquals(
            true,
            room.isFreeBetween(
                Instant.parse("2026-11-24T00:00:00Z"),
                Instant.parse("2026-11-24T02:00:00Z"),
            ),
        )
        assertFalse(
            room.isFreeBetween(
                Instant.parse("2026-11-24T01:30:00Z"),
                Instant.parse("2026-11-24T02:30:00Z"),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            val instant = Instant.parse("2026-11-24T02:00:00Z")
            room.occupationsBetween(instant, instant)
        }
    }

    @Test
    fun transcriptDecodesGradesFiltersBySemesterAndDeduplicatesEarnedCredits() {
        val transcript = ZzuJson.decodeFromString<Transcript>(TRANSCRIPT_JSON)

        assertEquals(3.72, transcript.gpa)
        assertEquals(5, transcript.rank?.rank)
        assertEquals(120, transcript.rank?.total)
        assertEquals(2, transcript.semesterGrades(SemesterId(194)).size)
        assertEquals(1, transcript.semesterGrades(SemesterId(190)).size)
        assertEquals(3.0, transcript.earnedCredits())

        val first = transcript.grades.first()
        assertEquals(StudyType.REQUIRED, first.studyType)
        assertEquals(GradeState.NORMAL, first.state)
        assertEquals("95", first.subGrades.single().items.single().score)

        val locked = transcript.grades[1]
        assertEquals(GradeState.EVALUATION_REQUIRED, locked.state)
        assertNull(locked.finalScore)
    }

    @Test
    fun examsDecodeScheduledAndUnscheduledEntries() {
        val exams = ZzuJson.decodeFromString<List<Exam>>(
            """[
                {
                    "id": 42, "courseCode": "CS201", "courseNameZh": "计算机网络",
                    "examType": {"id": 1, "nameZh": "期末考试", "code": "FINAL"},
                    "date": "2027-01-10", "weekday": 7,
                    "startTime": "2027-01-10T09:00:00+08:00[Asia/Shanghai]",
                    "endTime": "2027-01-10T11:00:00+08:00[Asia/Shanghai]",
                    "place": "主校区 南101", "seatNumber": "18"
                },
                {"courseNameZh": "毕业实习"}
            ]""",
        )

        val (scheduled, unscheduled) = exams
        assertEquals(true, scheduled.isScheduled)
        assertEquals(DayOfWeek.SUNDAY, scheduled.weekday)
        assertEquals(
            LocalDateTime(2027, 1, 10, 9, 0),
            scheduled.startTime?.toLocalDateTime(ZzuTimeZone),
        )
        assertEquals("18", scheduled.seatNumber)
        assertFalse(unscheduled.isScheduled)
        assertNull(unscheduled.date)
        assertNull(unscheduled.weekday)
    }

    @Test
    fun programCompletionDecodesRecursiveModulesAndTotalsCredits() {
        val completion = ZzuJson.decodeFromString<ProgramCompletion>(
            """{
                "cultivateType": {"id": 1, "nameZh": "主修", "code": "01"},
                "requiredCredits": 160.0, "requiredModuleCount": 2,
                "modules": [
                    {
                        "kind": {"id": 10, "nameZh": "专业必修", "code": "REQ"},
                        "requiredCredits": 80.0, "requiredCourseCount": 20, "requiredSubModuleCount": 1,
                        "passedCredits": 60.5, "takingCredits": 12.0, "exemptCredits": 2.0,
                        "status": "in_progress",
                        "children": [{
                            "kind": {"id": 11, "nameZh": "核心基础", "code": "CORE"},
                            "requiredCredits": 30.0, "requiredCourseCount": 0, "requiredSubModuleCount": 0,
                            "passedCredits": 30.0, "takingCredits": 0.0, "exemptCredits": 0.0,
                            "status": "passed",
                            "courses": [{
                                "courseId": 101, "courseCode": "CS101", "courseNameZh": "程序设计",
                                "credits": 3.0, "isCompulsory": true, "suggestedTerms": [1, 2],
                                "status": "passed", "score": "92", "gradePoint": 4.0,
                                "substituteCourses": [{"id": 99, "nameZh": "C语言", "code": "CS100"}]
                            }]
                        }]
                    },
                    {
                        "kind": {"id": 20, "nameZh": "通识选修", "code": "GEN"},
                        "requiredCredits": 10.0, "requiredCourseCount": 0, "requiredSubModuleCount": 0,
                        "passedCredits": 10.0, "takingCredits": 2.5, "exemptCredits": 0.0,
                        "status": "exempt"
                    }
                ],
                "outOfPlanCourses": [{
                    "courseId": 202, "courseCode": "ART01", "courseNameZh": "音乐鉴赏",
                    "isCompulsory": false, "status": "not_taken"
                }]
            }""",
        )

        assertEquals(70.5, completion.passedCredits())
        assertEquals(14.5, completion.takingCredits())
        val firstModule = completion.modules.first()
        assertEquals(CompletionStatus.IN_PROGRESS, firstModule.status)
        val childCourse = firstModule.children.single().courses.single()
        assertEquals(CompletionStatus.PASSED, childCourse.status)
        assertEquals(listOf(1, 2), childCourse.suggestedTerms)
        assertEquals("C语言", childCourse.substituteCourses.single().nameZh)
        assertEquals(CompletionStatus.EXEMPT, completion.modules[1].status)
        assertEquals(CompletionStatus.NOT_TAKEN, completion.outOfPlanCourses.single().status)
    }

    private fun sampleConflictTimetable(base: Timetable): Timetable {
        val firstSession = base.sessions.single()
        val overlappingExperiment = ClassSession(
            classId = 1002,
            scheduleGroupId = 2,
            weekIndex = 8,
            date = LocalDate(2026, 10, 26),
            weekday = DayOfWeek.MONDAY,
            startTime = Instant.parse("2026-10-26T00:30:00Z"),
            endTime = Instant.parse("2026-10-26T02:00:00Z"),
            startUnit = 2,
            endUnit = 3,
            customPlace = "物理楼 201",
            kind = SessionKind.EXPERIMENT,
            experimentProject = ExperimentProject(9, "E1", "分光计测量"),
        )
        val extraWeekSession = ClassSession(
            classId = 1001,
            scheduleGroupId = 3,
            weekIndex = 10,
            date = LocalDate(2026, 11, 9),
            weekday = DayOfWeek.MONDAY,
            startTime = Instant.parse("2026-11-09T00:00:00Z"),
            endTime = Instant.parse("2026-11-09T01:50:00Z"),
            startUnit = 1,
            endUnit = 2,
            kind = SessionKind("custom_kind"),
        )
        return Timetable(
            semester = base.semester,
            classes = base.classes,
            sessions = listOf(firstSession, overlappingExperiment, extraWeekSession),
        )
    }

    private fun assertContains(text: String, part: String) {
        assertEquals(true, part in text, "$text 不含 $part")
    }
}

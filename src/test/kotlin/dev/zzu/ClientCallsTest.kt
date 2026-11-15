package dev.zzu

import dev.zzu.cas.CasClient
import dev.zzu.cas.SsoService
import dev.zzu.cas.Tokens
import dev.zzu.eas.EasClient
import dev.zzu.eas.SemesterId
import dev.zzu.ecard.EcardClient
import dev.zzu.ecard.PaymentChannel
import dev.zzu.ecard.PaymentContext
import dev.zzu.ecard.PaymentDevice
import dev.zzu.ecard.RechargeOrder
import dev.zzu.internal.ZzuJson
import dev.zzu.internal.ZzuRpc
import dev.zzu.network.PortalClient
import dev.zzu.network.PortalEncryption
import dev.zzu.network.SelfServiceClient
import dev.zzu.network.SessionId
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ClientCallsTest {
    private fun TestScope.rpcOver(transport: FakeTransport) =
        ZzuRpc(transport, StandardTestDispatcher(testScheduler))

    @Test
    fun smsLoginSendsTheCodeThenRetries() = runTest {
        var loginAttempts = 0
        val transport = object : dev.zzu.internal.CasTransport by FakeTransport() {
            val methods = mutableListOf<String>()

            override suspend fun call(
                method: String,
                params: kotlinx.serialization.json.JsonObject?,
            ): ByteArray {
                methods += method
                if (method == "login" && ++loginAttempts == 1) {
                    throw ZzuException.Authentication(AuthenticationErrorKind.MFA_REQUIRED, "需要 MFA")
                }
                return "null".encodeToByteArray()
            }
        }
        val cas = CasClient(transport)

        cas.login { "123456" }

        assertEquals(listOf("login", "send_sms", "verify_sms", "login"), transport.methods)
    }

    @Test
    fun smsLoginDoesNotAskForACodeWhenMfaIsNotRequired() = runTest {
        val cas = CasClient(FakeTransport())

        cas.login { error("不应索取验证码") }
    }

    @Test
    fun smsLoginPropagatesOtherAuthenticationFailures() = runTest {
        val transport = object : dev.zzu.internal.CasTransport by FakeTransport() {
            override suspend fun call(
                method: String,
                params: kotlinx.serialization.json.JsonObject?,
            ): ByteArray = throw ZzuException.Authentication(
                AuthenticationErrorKind.INVALID_CREDENTIALS,
                "密码错误",
            )
        }

        val failure = assertFailsWith<ZzuException.Authentication> {
            CasClient(transport).login { error("不应索取验证码") }
        }

        assertEquals(AuthenticationErrorKind.INVALID_CREDENTIALS, failure.kind)
    }

    @Test
    fun casSendsTokensWithTheirWireNames() = runTest {
        val transport = FakeTransport()

        CasClient(transport).setTokens(Tokens("user", "refresh"))

        val (method, params) = transport.calls.single()
        assertEquals("set_tokens", method)
        assertEquals(
            buildJsonObject {
                put("userToken", "user")
                put("refreshToken", "refresh")
            },
            params,
        )
    }

    @Test
    fun casRequestsStandaloneServiceLoginUrlsByWireName() = runTest {
        val url = "https://mail.test/coremail/main.jsp?sid=test-session"
        val transport = FakeTransport { "\"$url\"" }
        val cas = CasClient(transport)

        val services = listOf(
            SsoService.EMAIL,
            SsoService.LIBRARY,
            SsoService.LIBRARY_RESERVATION,
            SsoService.LEAVE,
            SsoService.PRESIDENT_MAILBOX,
            SsoService.ECARD,
        )

        val urls = services.map { cas.serviceLoginUrl(it) }

        assertEquals(List(services.size) { url }, urls)
        assertEquals(setOf("service_login_url"), transport.calls.map { it.first }.toSet())
        assertEquals(
            listOf("email", "library", "library_reservation", "leave", "president_mailbox", "ecard"),
            transport.calls.map { it.second!!["service"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun casRequestsUndergradEasLoginUrlsWithAbiServiceNames() = runTest {
        val serviceNames: List<Pair<SsoService, String>> = listOf(
            SsoService.UndergradEas.STUDENT_STATUS to "student_status",
            SsoService.UndergradEas.PROGRAM_COMPLETION to "program_completion",
            SsoService.UndergradEas.EXAMS to "exams",
            SsoService.UndergradEas.ACADEMIC_WARNINGS to "academic_warnings",
            SsoService.UndergradEas.GRADES to "grades",
            SsoService.UndergradEas.TIMETABLE to "timetable",
            SsoService.UndergradEas.COURSE_OFFERINGS to "course_offerings",
            SsoService.UndergradEas.SCHOOL_CALENDAR to "school_calendar",
            SsoService.UndergradEas.AVAILABLE_CLASSROOMS to "available_classrooms",
            SsoService.UndergradEas.CLASSROOM_TIMETABLE to "classroom_timetable",
        )
        val url = "https://sso.test/login?idToken=a%2Bb%2Fc%3D&next=%2Fhome#complete"
        val transport = FakeTransport { "\"$url\"" }
        val cas = CasClient(transport, rpcOver(transport))

        val urls = serviceNames.map { (service, _) -> cas.serviceLoginUrl(service) }

        assertEquals(List(serviceNames.size) { url }, urls)
        assertEquals(List(serviceNames.size) { "service_login_url" }, transport.calls.map { it.first })
        assertEquals(
            serviceNames.map { (_, name) ->
                buildJsonObject {
                    put("service", buildJsonObject { put("undergrad_eas", name) })
                }
            },
            transport.calls.map { it.second!! },
        )
    }

    @Test
    fun missingTokensDecodeAsNull() = runTest {
        val cas = CasClient(FakeTransport(), rpcOver(FakeTransport()))

        assertNull(cas.tokens())
    }

    @Test
    fun derivedClientsComeFromTheCasTransport() {
        val transport = FakeTransport()
        val cas = CasClient(transport)

        EasClient(cas)
        EcardClient(cas)

        assertEquals(2, transport.derivedTransports)
    }

    @Test
    fun easOmitsNothingAndPassesTypedIds() = runTest {
        val transport = FakeTransport { "[]" }
        val eas = EasClient(rpcOver(transport))

        eas.teachingWeeks()
        eas.teachingWeeks(SemesterId(7))

        val (defaultParams, explicitParams) = transport.calls.map { it.second!! }
        assertEquals(JsonNull, defaultParams["semesterId"])
        assertEquals(JsonPrimitive(7L), explicitParams["semesterId"])
    }

    @Test
    fun easTeachingWeekDecodesTheResult() = runTest {
        val transport = FakeTransport { TEACHING_WEEK_JSON }

        val week = EasClient(rpcOver(transport)).teachingWeek(week = 3)

        assertEquals(3, week.weekIndex)
        assertEquals("teaching_week", transport.calls.single().first)
    }

    @Test
    fun easFormatsDatesForWeekIndex() = runTest {
        val transport = FakeTransport { "12" }

        val week = EasClient(rpcOver(transport)).weekIndex(LocalDate(2026, 3, 2))

        assertEquals(12, week)
        assertEquals("2026-03-02", transport.calls.single().second!!["date"]!!.jsonPrimitive.content)
    }

    @Test
    fun easTreatsANullWeekIndexAsOutOfTerm() = runTest {
        assertNull(EasClient(rpcOver(FakeTransport())).weekIndex(LocalDate(2026, 8, 1)))
    }

    @Test
    fun ecardBalanceIsExact() = runTest {
        val balance = EcardClient(rpcOver(FakeTransport { "\"12.30\"" })).balance()

        assertEquals(BigDecimal("12.30"), balance)
    }

    @Test
    fun ecardPassesOrdersBackUntouched() = runTest {
        val order = ZzuJson.decodeFromString<RechargeOrder>(ORDER_JSON)
        val transport = FakeTransport { "[]" }

        EcardClient(rpcOver(transport)).paymentChannels(order)

        assertEquals(ZzuJson.parseToJsonElement(ORDER_JSON), transport.calls.single().second!!["order"])
    }

    @Test
    fun ecardPreparePaymentDefaultsToAnEmptyContext() = runTest {
        val order = ZzuJson.decodeFromString<RechargeOrder>(ORDER_JSON)
        val transport = FakeTransport { """{"kind": "redirect", "url": "https://p.test/"}""" }
        val channel = PaymentChannel("0106", "支付宝")

        EcardClient(rpcOver(transport)).preparePayment(order, channel)

        val params = transport.calls.single().second!!
        assertEquals(buildJsonObject { }, params["context"])
        assertEquals(
            buildJsonObject {
                put("id", "0106")
                put("name", "支付宝")
            },
            params["channel"],
        )
    }

    @Test
    fun ecardPreparePaymentEncodesAnExplicitContext() = runTest {
        val order = ZzuJson.decodeFromString<RechargeOrder>(ORDER_JSON)
        val transport = FakeTransport { """{"kind": "redirect", "url": "https://p.test/"}""" }

        EcardClient(rpcOver(transport)).preparePayment(
            order,
            PaymentChannel("0106", "支付宝"),
            PaymentContext(PaymentDevice.IOS, "10.1.2.3"),
        )

        assertEquals(
            buildJsonObject {
                put("device", "ios")
                put("payerIp", "10.1.2.3")
            },
            transport.calls.single().second!!["context"],
        )
    }

    @Test
    fun portalEncodesEncryptionInSnakeCase() = runTest {
        val transport = FakeTransport {
            """{"isSuccess": false, "result": 0, "message": "失败", "retCode": 1}"""
        }

        val result = PortalClient(rpcOver(transport)).login("a@cmcc", "p", PortalEncryption.XOR)

        assertEquals(false, result.isSuccess)
        assertEquals(JsonPrimitive("xor"), transport.calls.single().second!!["encryption"])
    }

    @Test
    fun selfServiceKicksByTypedSessionId() = runTest {
        val transport = FakeTransport()

        SelfServiceClient(rpcOver(transport)).kickDevice(SessionId("sid-1"))

        assertEquals(
            "kick_device" to buildJsonObject { put("sessionId", "sid-1") },
            transport.calls.single(),
        )
    }

    @Test
    fun malformedResultsBecomeDecodeErrorsWithoutTheirContent() = runTest {
        val transport = FakeTransport { """{"secret": "response-secret"}""" }

        val failure = assertFailsWith<ZzuException.Decode> {
            EasClient(rpcOver(transport)).teachingWeek(1)
        }

        assertTrue("response-secret" !in failure.toString(), failure.toString())
    }

    @Test
    fun closingAClientClosesItsTransport() {
        val transport = FakeTransport()

        EasClient(ZzuRpc(transport)).close()

        assertTrue(transport.isClosed)
    }

    @Test
    fun timeoutMustCoverAtLeastOneMillisecond() {
        assertFailsWith<IllegalArgumentException> {
            ZzuClientConfig().timeout = 500.milliseconds / 1000
        }
    }
}

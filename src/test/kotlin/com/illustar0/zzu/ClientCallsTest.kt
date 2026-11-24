package com.illustar0.zzu

import com.illustar0.zzu.cas.CasClient
import com.illustar0.zzu.cas.SmsChallenge
import com.illustar0.zzu.cas.SsoService
import com.illustar0.zzu.cas.Tokens
import com.illustar0.zzu.eas.EasClient
import com.illustar0.zzu.eas.SemesterId
import com.illustar0.zzu.ecard.EcardClient
import com.illustar0.zzu.ecard.PaymentChannel
import com.illustar0.zzu.ecard.PaymentContext
import com.illustar0.zzu.ecard.PaymentDevice
import com.illustar0.zzu.ecard.RechargeOrder
import com.illustar0.zzu.internal.ZzuJson
import com.illustar0.zzu.internal.ZzuRpc
import com.illustar0.zzu.internal.ZzuTransport
import com.illustar0.zzu.network.PortalClient
import com.illustar0.zzu.network.PortalEncryption
import com.illustar0.zzu.network.SelfServiceClient
import com.illustar0.zzu.network.SessionId
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ClientCallsTest {
    private fun TestScope.rpcOver(transport: ZzuTransport) =
        ZzuRpc(transport, StandardTestDispatcher(testScheduler))

    @Test
    fun smsLoginSendsTheCodeThenRetries() = runTest {
        var loginAttempts = 0
        var receivedChallenge: SmsChallenge? = null
        val transport = object : com.illustar0.zzu.internal.CasTransport by FakeTransport() {
            val methods = mutableListOf<String>()

            override suspend fun call(
                method: String,
                params: kotlinx.serialization.json.JsonObject?,
            ): ByteArray {
                methods += method
                if (method == "login" && ++loginAttempts == 1) {
                    throw ZzuException.Authentication(AuthenticationErrorKind.MFA_REQUIRED, "需要 MFA")
                }
                if (method == "send_sms") {
                    return """{"securePhone": "138****1234"}""".encodeToByteArray()
                }
                return "null".encodeToByteArray()
            }
        }
        val cas = CasClient(transport, rpcOver(transport))

        cas.login { challenge ->
            receivedChallenge = challenge
            "123456"
        }

        assertEquals(SmsChallenge("138****1234"), receivedChallenge)
        assertEquals(listOf("login", "send_sms", "verify_sms", "login"), transport.methods)
    }

    @Test
    fun casPreparesAndSendsSmsWithMaskedPhone() = runTest {
        val transport = FakeTransport { method ->
            when (method) {
                "prepare_sms" -> """{"securePhone": "138****5678"}"""
                "send_sms" -> """{"securePhone": null}"""
                else -> "null"
            }
        }
        val cas = CasClient(transport, rpcOver(transport))

        assertEquals(SmsChallenge("138****5678"), cas.prepareSms())
        assertEquals(SmsChallenge(null), cas.sendSms())
        assertEquals(listOf("prepare_sms", "send_sms"), transport.calls.map { it.first })
        assertTrue(transport.calls.all { it.second == null })
    }

    @Test
    fun smsLoginDoesNotAskForACodeWhenMfaIsNotRequired() = runTest {
        val cas = CasClient(FakeTransport())

        cas.login { error("不应索取验证码") }
    }

    @Test
    fun smsLoginPropagatesOtherAuthenticationFailures() = runTest {
        val transport = object : com.illustar0.zzu.internal.CasTransport by FakeTransport() {
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
    fun casReadsConfiguredDeviceId() = runTest {
        val transport = FakeTransport { "\"device-123\"" }

        val deviceId = CasClient(transport, rpcOver(transport)).deviceId()

        assertEquals("device-123", deviceId)
        assertEquals("device_id" to null, transport.calls.single())
    }

    @Test
    fun casUpdatesDeviceTrustWithWireNames() = runTest {
        val transport = FakeTransport()

        CasClient(transport, rpcOver(transport)).setDeviceTrust("device-1", isTrusted = true)

        assertEquals(
            "set_device_trust" to buildJsonObject {
                put("deviceId", "device-1")
                put("isTrusted", true)
            },
            transport.calls.single(),
        )
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
    fun casRequestsStandaloneServiceSsoUrlsByWireName() = runTest {
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

        val urls = services.map { cas.serviceSsoUrl(it) }

        assertEquals(List(services.size) { url }, urls)
        assertEquals(setOf("service_sso_url"), transport.calls.map { it.first }.toSet())
        assertEquals(
            listOf("email", "library", "library_reservation", "leave", "president_mailbox", "ecard"),
            transport.calls.map { it.second!!["service"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun casRequestsUndergradEasSsoUrlsWithAbiServiceNames() = runTest {
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

        val urls = serviceNames.map { (service, _) -> cas.serviceSsoUrl(service) }

        assertEquals(List(serviceNames.size) { url }, urls)
        assertEquals(List(serviceNames.size) { "service_sso_url" }, transport.calls.map { it.first })
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
    fun easTimetablePassesTypedSemesterIdAndExperimentFlag() = runTest {
        val transport = FakeTransport { TIMETABLE_JSON }
        val eas = EasClient(rpcOver(transport))

        val defaultTimetable = eas.timetable()
        eas.timetable(SemesterId(7), includeExperiments = false)

        assertEquals(SemesterId(194), defaultTimetable.semester.id)
        assertEquals(listOf("timetable", "timetable"), transport.calls.map { it.first })
        val (defaultParams, explicitParams) = transport.calls.map { it.second!! }
        assertEquals(JsonNull, defaultParams["semesterId"])
        assertEquals(JsonPrimitive(true), defaultParams["includeExperiments"])
        assertEquals(JsonPrimitive(7L), explicitParams["semesterId"])
        assertEquals(JsonPrimitive(false), explicitParams["includeExperiments"])
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
            EasClient(rpcOver(transport)).timetable()
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

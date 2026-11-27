package com.illustar0.zzu

import com.illustar0.zzu.cas.CasClient
import com.illustar0.zzu.cas.SsoService
import com.illustar0.zzu.eas.CalendarExportOptions
import com.illustar0.zzu.eas.ClassroomFetchOptions
import com.illustar0.zzu.eas.ClassroomQuery
import com.illustar0.zzu.eas.EasClient
import com.illustar0.zzu.eas.Timetable
import com.illustar0.zzu.ecard.EcardClient
import com.illustar0.zzu.ecard.PaymentChannel
import com.illustar0.zzu.ecard.RechargeOrder
import com.illustar0.zzu.ecard.RoomId
import com.illustar0.zzu.ecard.TransactionQuery
import com.illustar0.zzu.internal.ZzuJson
import com.illustar0.zzu.mail.MailClient
import com.illustar0.zzu.network.PortalClient
import com.illustar0.zzu.network.PortalSourceAddress
import com.illustar0.zzu.network.SelfServiceClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate

/** 使用真实的平台原生 `libzzu` 走完 FFI 与异步回调链路，全部在本地回路离线完成。 */
class NativeClientTest {
    private val lazyCas = lazy { CasClient("", "", "test-device") { timeout = 5.seconds } }
    private val cas by lazyCas

    @BeforeTest
    fun requireNativeLibrary() {
        assumeNativeLibraryAvailable()
    }

    @AfterTest
    fun closeClient() {
        if (lazyCas.isInitialized()) cas.close()
    }

    @Test
    fun asyncCallsComeBackThroughTheCallback() = runTest {
        assertFalse(cas.isLoggedIn())
        assertEquals("test-device", cas.deviceId())
        assertNull(cas.tokens())
    }

    @Test
    fun blankCasDeviceIdIsRejectedSynchronously() {
        assertFailsWith<ZzuException.InvalidInput> { CasClient("", "", "") }
        assertFailsWith<ZzuException.InvalidInput> { CasClient("", "", "   ") }
    }

    @Test
    fun deviceTrustValidatesInputAndRequiresLogin() = runTest {
        assertFailsWith<ZzuException.InvalidInput> { cas.setDeviceTrust("", isTrusted = true) }
        assertFailsWith<ZzuException.InvalidInput> { cas.devices(page = 0) }
        assertFailsWith<ZzuException.InvalidInput> { cas.devices(pageSize = 0) }

        val failure = assertFailsWith<ZzuException.Authentication> {
            cas.setDeviceTrust("test-device", isTrusted = false)
        }
        assertEquals(AuthenticationErrorKind.NOT_AUTHENTICATED, failure.kind)
        val devicesFailure = assertFailsWith<ZzuException.Authentication> { cas.devices() }
        assertEquals(AuthenticationErrorKind.NOT_AUTHENTICATED, devicesFailure.kind)
    }

    @Test
    fun errorPayloadsBecomeTypedExceptions() = runTest {
        val failure = assertFailsWith<ZzuException.Authentication> { cas.userToken() }

        assertEquals(AuthenticationErrorKind.NOT_AUTHENTICATED, failure.kind)
        assertNull(failure.request)

        val portraitFailure = assertFailsWith<ZzuException.Authentication> { cas.portrait() }
        assertEquals(AuthenticationErrorKind.NOT_AUTHENTICATED, portraitFailure.kind)
    }

    @Test
    fun standaloneSsoServicesAreAcceptedAndRequireLogin() = runTest {
        listOf(
            SsoService.EMAIL,
            SsoService.LIBRARY,
            SsoService.LIBRARY_RESERVATION,
            SsoService.LEAVE,
            SsoService.PRESIDENT_MAILBOX,
            SsoService.ECARD,
        ).forEach { service ->
            val failure = assertFailsWith<ZzuException.Authentication> { cas.serviceSsoUrl(service) }

            assertEquals(AuthenticationErrorKind.NOT_AUTHENTICATED, failure.kind)
        }
    }

    @Test
    fun invalidConfigurationIsReportedSynchronously() {
        assertFailsWith<ZzuException.InvalidInput> { PortalClient("not a url", "10.0.0.1") }
    }

    @Test
    fun portalAcceptsEverySourceAddress() {
        PortalSourceAddress.entries.forEach { sourceAddress ->
            PortalClient("http://10.0.0.1:801", "10.0.0.2", sourceAddress).close()
        }
    }

    @Test
    fun derivedClientsOutliveTheCasHandle() = runTest {
        val eas = EasClient(cas)
        val ecard = EcardClient(cas)
        val mail = MailClient(cas)

        cas.close()

        val failure = assertFailsWith<ZzuException.Authentication> { eas.login() }
        assertEquals(AuthenticationErrorKind.NOT_AUTHENTICATED, failure.kind)
        assertFalse(ecard.isLoggedIn())
        val codeFailure = assertFailsWith<ZzuException.Authentication> { ecard.paymentCode() }
        assertEquals(AuthenticationErrorKind.NOT_AUTHENTICATED, codeFailure.kind)
        assertFalse(mail.isLoggedIn())
        assertFailsWith<ZzuException.InvalidInput> { mail.messages(page = 0) }
        assertFailsWith<ZzuException.InvalidInput> { mail.messages(pageSize = 0) }
        val mailFailure = assertFailsWith<ZzuException.Authentication> { mail.unreadCount() }
        assertEquals(AuthenticationErrorKind.NOT_AUTHENTICATED, mailFailure.kind)
        val messagesFailure = assertFailsWith<ZzuException.Authentication> { mail.messages() }
        assertEquals(AuthenticationErrorKind.NOT_AUTHENTICATED, messagesFailure.kind)
        eas.close()
        ecard.close()
        mail.close()
    }

    @Test
    fun closedClientsRejectNewCalls() = runTest {
        cas.close()

        assertFailsWith<IllegalStateException> { cas.isLoggedIn() }
        assertFailsWith<IllegalStateException> { EasClient(cas) }
        cas.close()
    }

    @Test
    fun roomIdsAreValidatedLocally() {
        assertEquals("1-2--3-301", RoomId.parse("1-2--3-301").value)
        assertFailsWith<ZzuException.InvalidInput> { RoomId.parse("invalid-room") }
        assertFailsWith<ZzuException.InvalidInput> { RoomId.parse("") }
    }

    @Test
    fun checkoutUrlIsComputedLocally() {
        val order = ZzuJson.decodeFromString<RechargeOrder>(ORDER_JSON)

        val url = order.checkoutUrlFor(PaymentChannel("0106", "支付宝"))

        assertTrue("callPaywayid=0106" in url, url)
        assertFailsWith<ZzuException.InvalidInput> {
            order.checkoutUrlFor(PaymentChannel("", "无效"))
        }
    }

    @Test
    fun ecardTransactionsValidateDateRangeAndPagination() = runTest {
        EcardClient(cas).use { ecard ->
            val valid = TransactionQuery(LocalDate(2026, 11, 1), LocalDate(2026, 11, 10))
            assertFailsWith<ZzuException.InvalidInput> {
                ecard.transactions(TransactionQuery(LocalDate(2026, 11, 10), LocalDate(2026, 11, 1)))
            }
            assertFailsWith<ZzuException.InvalidInput> { ecard.transactions(valid, page = 0) }
            assertFailsWith<ZzuException.InvalidInput> { ecard.transactions(valid, pageSize = 0) }

            val failure = assertFailsWith<ZzuException.Authentication> { ecard.transactions(valid) }
            assertEquals(AuthenticationErrorKind.NOT_AUTHENTICATED, failure.kind)
            val energyFailure = assertFailsWith<ZzuException.Authentication> { ecard.energyAccount() }
            assertEquals(AuthenticationErrorKind.NOT_AUTHENTICATED, energyFailure.kind)
        }
    }

    @Test
    fun classroomQueriesValidateParametersAndRequireLogin() = runTest {
        EasClient(cas).use { eas ->
            val query = ClassroomQuery(date = LocalDate(2026, 11, 24))
            val catalogFailure = assertFailsWith<ZzuException.Authentication> { eas.classroomCatalog() }
            assertEquals(AuthenticationErrorKind.NOT_AUTHENTICATED, catalogFailure.kind)
            val transcriptFailure = assertFailsWith<ZzuException.Authentication> { eas.transcript() }
            assertEquals(AuthenticationErrorKind.NOT_AUTHENTICATED, transcriptFailure.kind)
            val examsFailure = assertFailsWith<ZzuException.Authentication> { eas.exams() }
            assertEquals(AuthenticationErrorKind.NOT_AUTHENTICATED, examsFailure.kind)
            val planFailure = assertFailsWith<ZzuException.Authentication> { eas.programCompletion() }
            assertEquals(AuthenticationErrorKind.NOT_AUTHENTICATED, planFailure.kind)

            assertFailsWith<ZzuException.InvalidInput> {
                eas.classroomOccupanciesPage(query, page = 0)
            }
            assertFailsWith<ZzuException.InvalidInput> {
                eas.classroomOccupancies(query, ClassroomFetchOptions(concurrency = 0))
            }
            assertFailsWith<ZzuException.InvalidInput> {
                eas.classroomOccupanciesPage(
                    ClassroomQuery(LocalDate(2026, 11, 24), minSeats = 100, maxSeats = 50),
                )
            }
        }
    }

    @Test
    fun decodedTimetablesRenderThroughTheNativeCalendar() {
        val timetable = ZzuJson.decodeFromString<Timetable>(TIMETABLE_JSON)

        val calendar = timetable.toICalendar(
            CalendarExportOptions(
                weeks = listOf(8),
                alarmMinutesBefore = listOf(15),
                calendarName = "我的课表",
            ),
        )

        assertTrue("BEGIN:VEVENT" in calendar, calendar)
        assertTrue("DTSTART:20261026T000000Z" in calendar, calendar)
        assertTrue("程序设计" in calendar, calendar)
        assertTrue("X-WR-CALNAME:我的课表" in calendar, calendar)
        assertTrue("BEGIN:VALARM" in calendar, calendar)
        assertFailsWith<ZzuException.InvalidInput> {
            timetable.toICalendar(CalendarExportOptions(weeks = listOf(0)))
        }
    }

    @Test
    fun aRefusedConnectionIsATransportError() = runTest {
        val port = unusedLoopbackPort()
        val service = SelfServiceClient("http://127.0.0.1:$port")

        val failure = assertFailsWith<ZzuException.Transport> { service.login("a", "b") }

        assertEquals(TransportErrorKind.CONNECTION, failure.kind)
        service.close()
    }

    @Test
    fun theTimeoutBoundsAStalledCall() = runTest {
        withStalledServer { url, _ ->
            val service = SelfServiceClient(url) { timeout = 100.milliseconds }

            assertFailsWith<ZzuException.Timeout> { service.login("a", "b") }
            service.close()
        }
    }

    @Test
    fun slowQueryDoesNotDelayAnotherQueryOnTheSameClient() = runTest {
        val queryStarted = CompletableDeferred<Unit>()
        val resumeQuery = CompletableDeferred<Unit>()
        withMockSelfServiceServer(queryStarted, resumeQuery) { url ->
            SelfServiceClient(url) { timeout = 30.seconds }.use { service ->
                service.login("test", "test")
                val slowQuery = async(Dispatchers.Default) { service.onlineDevices() }
                try {
                    withContextTimeout { queryStarted.await() }
                    assertTrue(withContextTimeout { service.onlineDevices() }.isEmpty())
                    assertFalse(slowQuery.isCompleted)
                } finally {
                    resumeQuery.complete(Unit)
                }
                assertTrue(withContextTimeout { slowQuery.await() }.isEmpty())
                service.logout()
                assertFalse(service.isLoggedIn())
            }
        }
    }

    @Test
    fun cancellationReachesAStalledCallAndLeavesTheClientUsable() = runTest {
        withStalledServer { url, accepted ->
            val service = SelfServiceClient(url) { timeout = 30.seconds }
            val call = launch(Dispatchers.Default) { service.login("a", "b") }
            withContextTimeout { accepted.await() }

            withContextTimeout { call.cancelAndJoin() }

            assertTrue(call.isCancelled)
            // 服务互斥锁若未随取消释放，这里会一直排队到超时。
            assertFalse(withContextTimeout { service.isLoggedIn() })
            service.close()
        }
    }

    private suspend fun <T> withContextTimeout(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { block() } }
}

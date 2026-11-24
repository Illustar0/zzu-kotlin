package com.illustar0.zzu

import com.illustar0.zzu.cas.CasClient
import com.illustar0.zzu.cas.SsoService
import com.illustar0.zzu.eas.EasClient
import com.illustar0.zzu.eas.TeachingWeek
import com.illustar0.zzu.eas.toICalendar
import com.illustar0.zzu.ecard.EcardClient
import com.illustar0.zzu.ecard.PaymentChannel
import com.illustar0.zzu.ecard.RechargeOrder
import com.illustar0.zzu.ecard.RoomId
import com.illustar0.zzu.internal.ZzuJson
import com.illustar0.zzu.network.PortalClient
import com.illustar0.zzu.network.PortalSourceAddress
import com.illustar0.zzu.network.SelfServiceClient
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
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
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

/** 用真实的桌面版 libzzu 走完 JNA 与回调链路，全部离线。 */
class NativeClientTest {
    private val lazyCas = lazy { CasClient("", "", "test-device") { timeout = 5.seconds } }
    private val cas by lazyCas

    @BeforeTest
    fun requireNativeLibrary() {
        val directory = System.getProperty("jna.library.path")?.let(::File)
        assumeTrue(
            "本机平台没有桌面版 libzzu 发布包",
            directory?.listFiles()?.isNotEmpty() == true,
        )
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

        val failure = assertFailsWith<ZzuException.Authentication> {
            cas.setDeviceTrust("test-device", isTrusted = false)
        }
        assertEquals(AuthenticationErrorKind.NOT_AUTHENTICATED, failure.kind)
    }

    @Test
    fun errorPayloadsBecomeTypedExceptions() = runTest {
        val failure = assertFailsWith<ZzuException.Authentication> { cas.userToken() }

        assertEquals(AuthenticationErrorKind.NOT_AUTHENTICATED, failure.kind)
        assertNull(failure.request)
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

        cas.close()

        val failure = assertFailsWith<ZzuException.Authentication> { eas.login() }
        assertEquals(AuthenticationErrorKind.NOT_AUTHENTICATED, failure.kind)
        assertFalse(ecard.isLoggedIn())
        eas.close()
        ecard.close()
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
    fun decodedTimetablesRenderThroughTheNativeCalendar() {
        val week = ZzuJson.decodeFromString<TeachingWeek>(TEACHING_WEEK_JSON)

        val calendar = week.toICalendar()

        assertTrue("BEGIN:VEVENT" in calendar, calendar)
        assertTrue("DTSTART:20261026T000000Z" in calendar, calendar)
        assertTrue("程序设计" in calendar, calendar)
    }

    @Test
    fun aRefusedConnectionIsATransportError() = runTest {
        val port = ServerSocket(0, 1, loopback).use { it.localPort }
        val service = SelfServiceClient("http://127.0.0.1:$port")

        val failure = assertFailsWith<ZzuException.Transport> { service.login("a", "b") }

        assertEquals(TransportErrorKind.CONNECTION, failure.kind)
        service.close()
    }

    @Test
    fun theTimeoutBoundsAStalledCall() = runTest {
        withStalledServer { url ->
            val service = SelfServiceClient(url) { timeout = 100.milliseconds }

            assertFailsWith<ZzuException.Timeout> { service.login("a", "b") }
            service.close()
        }
    }

    @Test
    fun slowQueryDoesNotDelayAnotherQueryOnTheSameClient() = runTest {
        val server = HttpServer.create(InetSocketAddress(loopback, 0), 0)
        val executor = Executors.newCachedThreadPool()
        val queryStarted = CompletableDeferred<Unit>()
        val resumeQuery = CountDownLatch(1)
        val queryCount = AtomicInteger()
        try {
            server.executor = executor
            server.createContext("/") { exchange ->
                exchange.use {
                    it.requestBody.use { body -> body.readBytes() }
                    val (status, response) = when (it.requestURI.path) {
                        "/Self/login/" -> 200 to "<input name=\"checkcode\" value=\"test\">"
                        "/Self/login/randomCode" -> 200 to "test"
                        "/Self/login/verify" -> {
                            it.responseHeaders.set("Location", "/Self/dashboard")
                            302 to "redirect"
                        }
                        "/Self/dashboard" -> 200 to "ready"
                        "/Self/dashboard/getOnlineList" -> {
                            if (queryCount.getAndIncrement() == 0) {
                                queryStarted.complete(Unit)
                                resumeQuery.await()
                            }
                            200 to "[]"
                        }
                        "/Self/login/logout" -> 200 to "ok"
                        else -> 404 to "not found"
                    }
                    val bytes = response.encodeToByteArray()
                    it.sendResponseHeaders(status, bytes.size.toLong())
                    it.responseBody.use { body -> body.write(bytes) }
                }
            }
            server.start()
            SelfServiceClient("http://127.0.0.1:${server.address.port}") { timeout = 30.seconds }.use { service ->
                service.login("test", "test")
                val slowQuery = async(Dispatchers.Default) { service.onlineDevices() }
                try {
                    withContextTimeout { queryStarted.await() }
                    assertTrue(withContextTimeout { service.onlineDevices() }.isEmpty())
                    assertFalse(slowQuery.isCompleted)
                } finally {
                    resumeQuery.countDown()
                }
                assertTrue(withContextTimeout { slowQuery.await() }.isEmpty())
                service.logout()
                assertFalse(service.isLoggedIn())
            }
        } finally {
            resumeQuery.countDown()
            server.stop(0)
            executor.shutdownNow()
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

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    private suspend fun <T> withContextTimeout(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(Dispatchers.Default) { withTimeout(10.seconds) { block() } }

    private suspend fun withStalledServer(block: suspend (url: String) -> Unit) {
        withStalledServer { url, _ -> block(url) }
    }

    /** 接受连接但永不响应的服务器；`accepted` 在首个连接到达时完成，说明调用已在途。 */
    private suspend fun withStalledServer(
        block: suspend (url: String, accepted: CompletableDeferred<Unit>) -> Unit,
    ) {
        val accepted = CompletableDeferred<Unit>()
        val connections = mutableListOf<AutoCloseable>()
        ServerSocket(0, 8, loopback).use { server ->
            val acceptor = thread(isDaemon = true) {
                runCatching {
                    while (true) {
                        connections += server.accept()
                        accepted.complete(Unit)
                    }
                }
            }
            block("http://127.0.0.1:${server.localPort}", accepted)
            server.close()
            acceptor.join()
        }
        connections.forEach(AutoCloseable::close)
    }
}

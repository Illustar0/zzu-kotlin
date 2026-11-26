package com.illustar0.zzu

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
private const val ACCEPT_BACKLOG: Int = 8

internal actual fun isNativeLibraryAvailable(): Boolean {
    val directory = System.getProperty("jna.library.path")?.let(::File)
    return directory?.listFiles()?.isNotEmpty() == true
}

internal actual fun assumeNativeLibraryAvailable() {
    assumeTrue("本机平台没有桌面版 libzzu 发布包", isNativeLibraryAvailable())
}

internal actual fun unusedLoopbackPort(): Int =
    ServerSocket(0, 1, loopback).use { it.localPort }

/** 接受连接但永不响应的本地回路服务器；`accepted` 在首个连接到达时完成，说明调用已在途。 */
internal actual suspend fun withStalledServer(
    block: suspend (url: String, accepted: CompletableDeferred<Unit>) -> Unit,
) {
    val accepted = CompletableDeferred<Unit>()
    val connections = mutableListOf<AutoCloseable>()
    ServerSocket(0, ACCEPT_BACKLOG, loopback).use { server ->
        val acceptor = thread(isDaemon = true) {
            runCatching {
                while (true) {
                    connections += server.accept()
                    accepted.complete(Unit)
                }
            }
        }
        try {
            block("http://127.0.0.1:${server.localPort}", accepted)
        } finally {
            server.close()
            acceptor.join()
        }
    }
    connections.forEach(AutoCloseable::close)
}

internal actual suspend fun withMockSelfServiceServer(
    queryStarted: CompletableDeferred<Unit>,
    resumeQuery: CompletableDeferred<Unit>,
    block: suspend (url: String) -> Unit,
) {
    val server = HttpServer.create(InetSocketAddress(loopback, 0), 0)
    val executor = Executors.newCachedThreadPool()
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
                            runBlocking { resumeQuery.await() }
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
        block("http://127.0.0.1:${server.address.port}")
    } finally {
        resumeQuery.complete(Unit)
        server.stop(0)
        executor.shutdownNow()
    }
}

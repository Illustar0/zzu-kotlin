@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package com.illustar0.zzu

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import platform.CoreFoundation.CFSwapInt16BigToHost
import platform.posix.AF_INET
import platform.posix.SHUT_RDWR
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar

private const val ACCEPT_BACKLOG: Int = 8
private const val READ_CHUNK_BYTES: Int = 4096
private const val HEADER_DELIMITER_LENGTH: Int = 4
private const val LOOPBACK_LAST_OCTET_INDEX: Int = 3
private const val LOOPBACK_FIRST_OCTET: UByte = 127u

internal actual fun isNativeLibraryAvailable(): Boolean = true

internal actual fun assumeNativeLibraryAvailable() = Unit

internal actual fun unusedLoopbackPort(): Int {
    val (serverFd, port) = bindLoopbackSocket()
    close(serverFd)
    return port
}

internal actual suspend fun withStalledServer(
    block: suspend (url: String, accepted: CompletableDeferred<Unit>) -> Unit,
) {
    val accepted = CompletableDeferred<Unit>()
    val (serverFd, port) = bindLoopbackSocket()
    val clientFds = mutableListOf<Int>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val acceptor = scope.launch {
        while (true) {
            val clientFd = accept(serverFd, null, null)
            if (clientFd < 0) break
            enableSocketOption(clientFd, SO_NOSIGPIPE)
            clientFds += clientFd
            accepted.complete(Unit)
        }
    }
    try {
        block("http://127.0.0.1:$port", accepted)
    } finally {
        shutdown(serverFd, SHUT_RDWR)
        close(serverFd)
        acceptor.cancelAndJoin()
        clientFds.forEach(::close)
    }
}

internal actual suspend fun withMockSelfServiceServer(
    queryStarted: CompletableDeferred<Unit>,
    resumeQuery: CompletableDeferred<Unit>,
    block: suspend (url: String) -> Unit,
) {
    val (serverFd, port) = bindLoopbackSocket()
    val queryCount = AtomicInt(0)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val acceptor = scope.launch {
        while (true) {
            val clientFd = accept(serverFd, null, null)
            if (clientFd < 0) break
            enableSocketOption(clientFd, SO_NOSIGPIPE)
            launch {
                try {
                    val path = readHttpRequestPath(clientFd)
                    if (path != null) {
                        val responseBytes = buildSelfServiceResponse(
                            path,
                            queryCount,
                            queryStarted,
                            resumeQuery,
                        )
                        writeAll(clientFd, responseBytes)
                    }
                } finally {
                    close(clientFd)
                }
            }
        }
    }
    try {
        block("http://127.0.0.1:$port")
    } finally {
        resumeQuery.complete(Unit)
        shutdown(serverFd, SHUT_RDWR)
        close(serverFd)
        acceptor.cancelAndJoin()
    }
}

private fun bindLoopbackSocket(): Pair<Int, Int> = memScoped {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    check(fd >= 0) { "无法创建本地测试套接字" }
    enableSocketOption(fd, SO_REUSEADDR)
    enableSocketOption(fd, SO_NOSIGPIPE)

    val address = alloc<sockaddr_in>()
    address.sin_len = sizeOf<sockaddr_in>().toUByte()
    address.sin_family = AF_INET.convert()
    address.sin_port = 0u
    val ipBytes = address.sin_addr.ptr.reinterpret<UByteVar>()
    ipBytes[0] = LOOPBACK_FIRST_OCTET
    ipBytes[1] = 0u
    ipBytes[2] = 0u
    ipBytes[LOOPBACK_LAST_OCTET_INDEX] = 1u

    val bound = bind(fd, address.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert())
    if (bound != 0 || listen(fd, ACCEPT_BACKLOG) != 0) {
        close(fd)
        error("无法监听本地回路测试端口")
    }

    val actualAddress = alloc<sockaddr_in>()
    val addressLen = alloc<socklen_tVar>()
    addressLen.value = sizeOf<sockaddr_in>().convert()
    getsockname(fd, actualAddress.ptr.reinterpret<sockaddr>(), addressLen.ptr)
    fd to CFSwapInt16BigToHost(actualAddress.sin_port).toInt()
}

private fun enableSocketOption(fd: Int, option: Int) = memScoped {
    val enabled = alloc<IntVar>()
    enabled.value = 1
    setsockopt(fd, SOL_SOCKET, option, enabled.ptr, sizeOf<IntVar>().convert())
}

private fun readHttpRequestPath(clientFd: Int): String? {
    val buffer = ByteArray(READ_CHUNK_BYTES)
    val received = StringBuilder()
    var headerEnd = -1
    var expectedBodyLength = 0
    buffer.usePinned { pinned ->
        while (headerEnd < 0 || received.length < headerEnd + HEADER_DELIMITER_LENGTH + expectedBodyLength) {
            val count = recv(clientFd, pinned.addressOf(0), buffer.size.convert(), 0).toInt()
            if (count <= 0) break
            received.append(buffer.decodeToString(0, count))
            if (headerEnd < 0) {
                headerEnd = received.indexOf("\r\n\r\n")
                if (headerEnd >= 0) {
                    expectedBodyLength = received.substring(0, headerEnd)
                        .lineSequence()
                        .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                        ?.substringAfter(':')
                        ?.trim()
                        ?.toIntOrNull()
                        ?: 0
                }
            }
        }
    }
    val firstLineEnd = received.indexOf("\r\n")
    return if (firstLineEnd <= 0) {
        null
    } else {
        received.substring(0, firstLineEnd).split(' ').getOrNull(1)?.substringBefore('?')
    }
}

private suspend fun buildSelfServiceResponse(
    path: String,
    queryCount: AtomicInt,
    queryStarted: CompletableDeferred<Unit>,
    resumeQuery: CompletableDeferred<Unit>,
): ByteArray {
    val (statusLine, extraHeader, body) = when (path) {
        "/Self/login/" -> Triple("200 OK", "", "<input name=\"checkcode\" value=\"test\">")
        "/Self/login/randomCode" -> Triple("200 OK", "", "test")
        "/Self/login/verify" -> Triple("302 Found", "Location: /Self/dashboard\r\n", "redirect")
        "/Self/dashboard" -> Triple("200 OK", "", "ready")
        "/Self/dashboard/getOnlineList" -> {
            if (queryCount.fetchAndAdd(1) == 0) {
                queryStarted.complete(Unit)
                resumeQuery.await()
            }
            Triple("200 OK", "", "[]")
        }
        "/Self/login/logout" -> Triple("200 OK", "", "ok")
        else -> Triple("404 Not Found", "", "not found")
    }
    val bodyBytes = body.encodeToByteArray()
    val header = "HTTP/1.1 $statusLine\r\n" +
        extraHeader +
        "Connection: close\r\n" +
        "Content-Length: ${bodyBytes.size}\r\n\r\n"
    return header.encodeToByteArray() + bodyBytes
}

private fun writeAll(clientFd: Int, bytes: ByteArray) {
    if (bytes.isEmpty()) return
    bytes.usePinned { pinned ->
        var offset = 0
        while (offset < bytes.size) {
            val written = send(
                clientFd,
                pinned.addressOf(offset).reinterpret<ByteVar>(),
                (bytes.size - offset).convert(),
                0,
            ).toInt()
            if (written <= 0) break
            offset += written
        }
    }
}

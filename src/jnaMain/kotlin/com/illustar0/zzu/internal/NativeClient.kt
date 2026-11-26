package com.illustar0.zzu.internal

import com.illustar0.zzu.ZzuException
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.completeWith
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 读取当前运行平台需要显式注入 `libzzu` 的 PEM 根证书；为 `null` 时使用平台默认校验器。 */
internal expect fun platformRootCertificatesPem(): String?

/** 持有一个 libzzu 客户端句柄的 [ZzuTransport]；读写锁保证 `close` 不会与在发起的调用竞争。 */
private class NativeClient(
    private var handle: Pointer?,
    private val callFunction: NativeCallFunction,
    private val freeFunction: (Pointer) -> Unit,
) : ZzuTransport {
    private val handleLock = ReentrantReadWriteLock()

    fun <R> withHandle(use: (Pointer) -> R): R = handleLock.read {
        use(checkNotNull(handle) { "客户端已关闭" })
    }

    override suspend fun call(method: String, params: JsonObject?): ByteArray {
        val input = params?.toUtf8()
        return awaitNativeCall(method) { token, outCall ->
            withHandle { client ->
                callFunction(client, method, input, input.nativeSize, PendingCalls, token, outCall)
            }
        }
    }

    override fun close() {
        handleLock.write {
            handle?.let(freeFunction)
            handle = null
        }
    }
}

private class NativeCasClient(private val client: NativeClient) :
    CasTransport,
    ZzuTransport by client {
    override fun newEasTransport(): ZzuTransport = client.withHandle { cas ->
        NativeClient(
            newHandle { outEas, outError -> LibZzu.zzu_eas_new(cas, outEas, outError) },
            LibZzu::zzu_eas_call,
            LibZzu::zzu_eas_free,
        )
    }

    override fun newEcardTransport(): ZzuTransport = client.withHandle { cas ->
        NativeClient(
            newHandle { outEcard, outError -> LibZzu.zzu_ecard_new(cas, outEcard, outError) },
            LibZzu::zzu_ecard_call,
            LibZzu::zzu_ecard_free,
        )
    }

    override fun newMailTransport(): ZzuTransport = client.withHandle { cas ->
        NativeClient(
            newHandle { outMail, outError -> LibZzu.zzu_mail_new(cas, outMail, outError) },
            LibZzu::zzu_mail_call,
            LibZzu::zzu_mail_free,
        )
    }
}

internal actual fun nativeCasTransport(config: JsonObject): CasTransport {
    val input = config.toUtf8()
    val handle = newHandle { outCas, outError ->
        LibZzu.zzu_cas_new(runtime, input, input.nativeSize, outCas, outError)
    }
    return NativeCasClient(NativeClient(handle, LibZzu::zzu_cas_call, LibZzu::zzu_cas_free))
}

internal actual fun nativePortalTransport(config: JsonObject): ZzuTransport {
    val input = config.toUtf8()
    val handle = newHandle { outPortal, outError ->
        LibZzu.zzu_portal_new(runtime, input, input.nativeSize, outPortal, outError)
    }
    return NativeClient(handle, LibZzu::zzu_portal_call, LibZzu::zzu_portal_free)
}

internal actual fun nativeSelfServiceTransport(config: JsonObject): ZzuTransport {
    val input = config.toUtf8()
    val handle = newHandle { outService, outError ->
        LibZzu.zzu_self_service_new(runtime, input, input.nativeSize, outService, outError)
    }
    return NativeClient(handle, LibZzu::zzu_self_service_call, LibZzu::zzu_self_service_free)
}

/** `zzu_portal_discover`：探测发生在 Portal 客户端创建之前，直接挂在进程级运行时上执行。 */
internal actual suspend fun nativeDiscoverPortalBytes(options: JsonObject): ByteArray {
    val input = options.toUtf8()
    return awaitNativeCall("portal_discover") { token, outCall ->
        LibZzu.zzu_portal_discover(runtime, input, input.nativeSize, PendingCalls, token, outCall)
    }
}

/**
 * 发起一次异步调用并等待回调。
 *
 * 调用句柄全程只归本函数所有，回调不会触碰它，取消与释放不会和完成竞争。
 * 释放句柄不影响回调：完成后的 token 由回调清理，协程取消时同样如此。
 */
private suspend fun awaitNativeCall(
    name: String,
    start: (token: Pointer, outCall: PointerByReference) -> Int,
): ByteArray {
    val completion = CompletableDeferred<NativeCompletion>()
    val call = submitNativeCall(name, completion, start)
    val (status, payload) = try {
        completion.await()
    } catch (cancellation: CancellationException) {
        LibZzu.zzu_call_cancel(call)
        throw cancellation
    } finally {
        LibZzu.zzu_call_free(call)
    }
    if (status != ZZU_OK) throw zzuErrorOf(status, payload)
    return payload
}

private fun submitNativeCall(
    name: String,
    completion: CompletableDeferred<NativeCompletion>,
    start: (token: Pointer, outCall: PointerByReference) -> Int,
): Pointer {
    val outCall = PointerByReference()
    val token = PendingCalls.add(completion)
    var isSubmitted = false
    try {
        val status = start(token, outCall)
        if (status != ZZU_OK) throw ZzuException.InvalidInput("libzzu 拒绝了调用 $name")
        isSubmitted = true
        return outCall.value
    } finally {
        if (!isSubmitted) PendingCalls.remove(token)
    }
}

private class NativeCompletion(val status: Int, val payload: ByteArray) {
    operator fun component1(): Int = status

    operator fun component2(): ByteArray = payload
}

/** ABI 建议的 `token → 调用` 表：全进程一个回调，`user_data` 只传整数，回调对象永不被回收。 */
private object PendingCalls : NativeCallback {
    private val nextToken = AtomicLong(1)
    private val calls = ConcurrentHashMap<Long, CompletableDeferred<NativeCompletion>>()

    fun add(completion: CompletableDeferred<NativeCompletion>): Pointer {
        val token = nextToken.getAndIncrement()
        calls[token] = completion
        return Pointer.createConstant(token)
    }

    fun remove(token: Pointer) {
        calls.remove(Pointer.nativeValue(token))
    }

    override fun invoke(userData: Pointer, status: Int, payload: Pointer) {
        val completion = runCatching { NativeCompletion(status, takeBytes(payload)) }
        calls.remove(Pointer.nativeValue(userData))?.completeWith(completion)
    }
}

/** 进程级后台运行时：首次使用时创建，随进程结束。 */
private val runtime: Pointer by lazy {
    val options = platformRootCertificatesPem()?.let { pem ->
        buildJsonObject { put("rootCertificatesPem", pem) }.toUtf8()
    }
    newHandle { outRuntime, outError ->
        LibZzu.zzu_runtime_new(options, options.nativeSize, outRuntime, outError)
    }
}

/** 运行一个 `zzu_*_new` 函数，返回写入的句柄；失败时错误 JSON 写入第二个输出参数。 */
private inline fun newHandle(create: (PointerByReference, PointerByReference) -> Int): Pointer {
    val outHandle = PointerByReference()
    val outError = PointerByReference()
    val status = create(outHandle, outError)
    if (status != ZZU_OK) throw zzuErrorOf(status, takeBytes(outError.value))
    return outHandle.value
}

/** 复制并释放库分配的缓冲区。 */
internal fun takeBytes(buffer: Pointer): ByteArray = try {
    val length = LibZzu.zzu_buffer_len(buffer).toInt()
    LibZzu.zzu_buffer_data(buffer).getByteArray(0, length)
} finally {
    LibZzu.zzu_buffer_free(buffer)
}

internal val ByteArray?.nativeSize: SizeT
    get() = SizeT(this?.size?.toLong() ?: 0)

@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package com.illustar0.zzu.internal

import cnames.structs.ZzuBuffer
import cnames.structs.ZzuCall
import cnames.structs.ZzuCasClient
import cnames.structs.ZzuRuntime
import com.illustar0.zzu.ZzuException
import com.illustar0.zzu.internal.cinterop.ZzuCallback
import com.illustar0.zzu.internal.cinterop.zzu_abi_version
import com.illustar0.zzu.internal.cinterop.zzu_buffer_data
import com.illustar0.zzu.internal.cinterop.zzu_buffer_free
import com.illustar0.zzu.internal.cinterop.zzu_buffer_len
import com.illustar0.zzu.internal.cinterop.zzu_call_cancel
import com.illustar0.zzu.internal.cinterop.zzu_call_free
import com.illustar0.zzu.internal.cinterop.zzu_cas_call
import com.illustar0.zzu.internal.cinterop.zzu_cas_free
import com.illustar0.zzu.internal.cinterop.zzu_cas_new
import com.illustar0.zzu.internal.cinterop.zzu_eas_call
import com.illustar0.zzu.internal.cinterop.zzu_eas_free
import com.illustar0.zzu.internal.cinterop.zzu_eas_new
import com.illustar0.zzu.internal.cinterop.zzu_ecard_call
import com.illustar0.zzu.internal.cinterop.zzu_ecard_free
import com.illustar0.zzu.internal.cinterop.zzu_ecard_new
import com.illustar0.zzu.internal.cinterop.zzu_mail_call
import com.illustar0.zzu.internal.cinterop.zzu_mail_free
import com.illustar0.zzu.internal.cinterop.zzu_mail_new
import com.illustar0.zzu.internal.cinterop.zzu_portal_call
import com.illustar0.zzu.internal.cinterop.zzu_portal_discover
import com.illustar0.zzu.internal.cinterop.zzu_portal_free
import com.illustar0.zzu.internal.cinterop.zzu_portal_new
import com.illustar0.zzu.internal.cinterop.zzu_runtime_new
import com.illustar0.zzu.internal.cinterop.zzu_self_service_call
import com.illustar0.zzu.internal.cinterop.zzu_self_service_free
import com.illustar0.zzu.internal.cinterop.zzu_self_service_new
import com.illustar0.zzu.internal.cinterop.zzu_version
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.completeWith
import kotlinx.serialization.json.JsonObject
import platform.posix.size_t

private typealias AppleCallFunction<H> = (
    client: CPointer<H>,
    method: String,
    params: CPointer<UByteVar>?,
    paramsLength: size_t,
    callback: ZzuCallback,
    userData: COpaquePointer,
    outCall: CPointer<CPointerVar<ZzuCall>>,
) -> Int

/**
 * 持有一个 `libzzu` 原生客户端句柄的 [ZzuTransport]。
 *
 * 通过原子引用计数与高位关闭标记保护句柄指针：活跃调用入队期间不会释放句柄，
 * `close` 后若有入队中的同步调用，由最后一个退出的调用方完成释放。
 */
private class NativeClient<H : CPointed>(
    private val handle: CPointer<H>,
    private val callFunction: AppleCallFunction<H>,
    private val freeFunction: (CPointer<H>) -> Unit,
) : ZzuTransport {
    private val state = AtomicLong(1L)

    fun <R> withHandle(use: (CPointer<H>) -> R): R {
        acquireHandle()
        return try {
            use(handle)
        } finally {
            releaseHandle()
        }
    }

    override suspend fun call(method: String, params: JsonObject?): ByteArray {
        val input = params?.toUtf8()
        return awaitNativeCall(method) { userData, outCall ->
            withHandle { client ->
                input.useUBytePointer { ptr, len ->
                    callFunction(client, method, ptr, len, nativeCallback, userData, outCall)
                }
            }
        }
    }

    override fun close() {
        while (true) {
            val current = state.load()
            if (current < 0L) return
            val next = (current - 1L) or Long.MIN_VALUE
            if (state.compareAndSet(current, next)) {
                if (current == 1L) freeFunction(handle)
                return
            }
        }
    }

    private fun acquireHandle() {
        while (true) {
            val current = state.load()
            check(current >= 0L) { "客户端已关闭" }
            if (state.compareAndSet(current, current + 1L)) return
        }
    }

    private fun releaseHandle() {
        while (true) {
            val current = state.load()
            val next = current - 1L
            if (state.compareAndSet(current, next)) {
                if (next == Long.MIN_VALUE) freeFunction(handle)
                return
            }
        }
    }
}

private class NativeCasClient(private val client: NativeClient<ZzuCasClient>) :
    CasTransport,
    ZzuTransport by client {
    override fun newEasTransport(): ZzuTransport = client.withHandle { cas ->
        NativeClient(
            newHandle { outEas, outError -> zzu_eas_new(cas, outEas, outError) },
            { handle, method, params, len, cb, data, outCall ->
                zzu_eas_call(handle, method, params, len, cb, data, outCall)
            },
            ::zzu_eas_free,
        )
    }

    override fun newEcardTransport(): ZzuTransport = client.withHandle { cas ->
        NativeClient(
            newHandle { outEcard, outError -> zzu_ecard_new(cas, outEcard, outError) },
            { handle, method, params, len, cb, data, outCall ->
                zzu_ecard_call(handle, method, params, len, cb, data, outCall)
            },
            ::zzu_ecard_free,
        )
    }

    override fun newMailTransport(): ZzuTransport = client.withHandle { cas ->
        NativeClient(
            newHandle { outMail, outError -> zzu_mail_new(cas, outMail, outError) },
            { handle, method, params, len, cb, data, outCall ->
                zzu_mail_call(handle, method, params, len, cb, data, outCall)
            },
            ::zzu_mail_free,
        )
    }
}

internal actual fun nativeCasTransport(config: JsonObject): CasTransport {
    val input = config.toUtf8()
    val handle = input.useUBytePointer { ptr, len ->
        newHandle<ZzuCasClient> { outCas, outError -> zzu_cas_new(runtime, ptr, len, outCas, outError) }
    }
    val client = NativeClient(
        handle,
        { cas, method, params, len, cb, data, outCall ->
            zzu_cas_call(cas, method, params, len, cb, data, outCall)
        },
        ::zzu_cas_free,
    )
    return NativeCasClient(client)
}

internal actual fun nativePortalTransport(config: JsonObject): ZzuTransport {
    val input = config.toUtf8()
    val handle = input.useUBytePointer { ptr, len ->
        newHandle { outPortal, outError -> zzu_portal_new(runtime, ptr, len, outPortal, outError) }
    }
    return NativeClient(
        handle,
        { portal, method, params, len, cb, data, outCall ->
            zzu_portal_call(portal, method, params, len, cb, data, outCall)
        },
        ::zzu_portal_free,
    )
}

internal actual fun nativeSelfServiceTransport(config: JsonObject): ZzuTransport {
    val input = config.toUtf8()
    val handle = input.useUBytePointer { ptr, len ->
        newHandle { outService, outError -> zzu_self_service_new(runtime, ptr, len, outService, outError) }
    }
    return NativeClient(
        handle,
        { service, method, params, len, cb, data, outCall ->
            zzu_self_service_call(service, method, params, len, cb, data, outCall)
        },
        ::zzu_self_service_free,
    )
}

internal actual suspend fun nativeDiscoverPortalBytes(options: JsonObject): ByteArray {
    val input = options.toUtf8()
    return awaitNativeCall("portal_discover") { userData, outCall ->
        input.useUBytePointer { ptr, len ->
            zzu_portal_discover(runtime, ptr, len, nativeCallback, userData, outCall)
        }
    }
}

private suspend fun awaitNativeCall(
    name: String,
    start: (userData: COpaquePointer, outCall: CPointer<CPointerVar<ZzuCall>>) -> Int,
): ByteArray {
    val completion = CompletableDeferred<NativeCompletion>()
    val call = submitNativeCall(name, completion, start)
    val completed = try {
        completion.await()
    } catch (cancellation: CancellationException) {
        zzu_call_cancel(call)
        throw cancellation
    } finally {
        zzu_call_free(call)
    }
    if (completed.status != ZZU_OK) throw zzuErrorOf(completed.status, completed.payload)
    return completed.payload
}

private fun submitNativeCall(
    name: String,
    completion: CompletableDeferred<NativeCompletion>,
    start: (userData: COpaquePointer, outCall: CPointer<CPointerVar<ZzuCall>>) -> Int,
): CPointer<ZzuCall> = memScoped {
    val outCall = alloc<CPointerVar<ZzuCall>>()
    val reference = StableRef.create(completion)
    var isSubmitted = false
    try {
        val status = start(reference.asCPointer(), outCall.ptr)
        if (status != ZZU_OK) throw ZzuException.InvalidInput("libzzu 拒绝了调用 $name")
        isSubmitted = true
        checkNotNull(outCall.value) { "libzzu 未返回调用句柄 $name" }
    } finally {
        if (!isSubmitted) reference.dispose()
    }
}

private class NativeCompletion(val status: Int, val payload: ByteArray)

/** Rust 工作线程回调入口：必须捕获一切异常以防穿越 C 边界导致进程中止。 */
private val nativeCallback: ZzuCallback = staticCFunction { userData, status, payload ->
    val completion = runCatching { NativeCompletion(status, takeBytes(payload)) }
    val reference = userData?.asStableRef<CompletableDeferred<NativeCompletion>>() ?: return@staticCFunction
    val deferred = reference.get()
    reference.dispose()
    runCatching { deferred.completeWith(completion) }
}

private val abiChecked: Unit by lazy {
    verifyAbiVersion(zzu_abi_version().toInt()) {
        zzu_version()?.toKString() ?: "unknown"
    }
}

internal fun ensureAbiCompatible() {
    abiChecked
}

/** 进程级后台运行时；Apple 平台直接使用 `libzzu` 内置的系统 Security 框架校验根证书。 */
private val runtime: CPointer<ZzuRuntime> by lazy {
    ensureAbiCompatible()
    newHandle { outRuntime, outError ->
        zzu_runtime_new(null, 0u, outRuntime, outError)
    }
}

private inline fun <H : CPointed> newHandle(
    create: (CPointer<CPointerVar<H>>, CPointer<CPointerVar<ZzuBuffer>>) -> Int,
): CPointer<H> = memScoped {
    val outHandle = alloc<CPointerVar<H>>()
    val outError = alloc<CPointerVar<ZzuBuffer>>()
    val status = create(outHandle.ptr, outError.ptr)
    if (status != ZZU_OK) throw zzuErrorOf(status, takeBytes(outError.value))
    checkNotNull(outHandle.value) { "libzzu 未返回客户端句柄" }
}

internal fun takeBytes(buffer: CPointer<ZzuBuffer>?): ByteArray =
    if (buffer == null) {
        ByteArray(0)
    } else {
        try {
            val length = zzu_buffer_len(buffer).toInt()
            if (length == 0) ByteArray(0) else checkNotNull(zzu_buffer_data(buffer)).readBytes(length)
        } finally {
            zzu_buffer_free(buffer)
        }
    }

internal inline fun <R> ByteArray?.useUBytePointer(
    block: (pointer: CPointer<UByteVar>?, length: size_t) -> R,
): R = if (this == null || isEmpty()) {
    block(null, 0u)
} else {
    usePinned { pinned ->
        block(pinned.addressOf(0).reinterpret(), size.convert())
    }
}

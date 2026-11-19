package com.illustar0.zzu.internal

import com.illustar0.zzu.ZzuException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer

/** 按方法名与 JSON 参数调用一个客户端的通道；失败时抛出 [ZzuException]。 */
internal interface ZzuTransport : AutoCloseable {
    /** 返回结果 JSON 的 UTF-8 字节。 */
    suspend fun call(method: String, params: JsonObject?): ByteArray

    override fun close()
}

/** CAS 客户端的通道，额外负责派生共享其会话的教务与校园卡通道。 */
internal interface CasTransport : ZzuTransport {
    fun newEasTransport(): ZzuTransport

    fun newEcardTransport(): ZzuTransport
}

/** 在 [ZzuTransport] 之上解码结果。 */
internal class ZzuRpc(
    private val transport: ZzuTransport,
    private val decodingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable by transport {
    /** 调用结果为 `null` 的方法。 */
    suspend fun perform(method: String, params: JsonObject? = null) {
        transport.call(method, params)
    }

    suspend fun <T> fetch(
        method: String,
        params: JsonObject?,
        deserializer: DeserializationStrategy<T>,
    ): T = decodeOffCaller(transport.call(method, params), method, deserializer, decodingDispatcher)
}

internal suspend inline fun <reified T> ZzuRpc.fetch(method: String, params: JsonObject? = null): T =
    fetch(method, params, serializer<T>())

/** 课表可达数百 KB，解码不留在调用方线程上；解析失败转换为 [ZzuException.Decode]。 */
@Suppress("SwallowedException")
internal suspend fun <T> decodeOffCaller(
    payload: ByteArray,
    source: String,
    deserializer: DeserializationStrategy<T>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): T = try {
    withContext(dispatcher) { ZzuJson.decodeFromString(deserializer, payload.decodeToString()) }
} catch (exception: IllegalArgumentException) {
    // 原异常附带的响应片段可能含个人信息或支付凭据，只保留脱敏后的诊断信息。
    throw ZzuException.Decode(
        "$source 的结果解析失败",
        exception.message?.substringBefore("\nJSON input:"),
    )
}

internal inline fun <reified T> JsonObjectBuilder.putValue(key: String, value: T) {
    put(key, ZzuJson.encodeToJsonElement(value))
}

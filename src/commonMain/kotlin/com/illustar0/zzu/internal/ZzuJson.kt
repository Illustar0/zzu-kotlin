package com.illustar0.zzu.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** ABI 约定：结果对象可能新增字段，解析时须忽略未知字段。 */
internal val ZzuJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

internal fun JsonObject.toUtf8(): ByteArray =
    ZzuJson.encodeToString(JsonObject.serializer(), this).encodeToByteArray()

package dev.zzu

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 校园服务使用的时区；把课表等时刻换算成当地时间时用它。 */
public val ZzuTimeZone: TimeZone = TimeZone.of("Asia/Shanghai")

/** 各客户端共用的可选配置。 */
public class ZzuClientConfig internal constructor() {
    /** 单次操作的总时限（含排队等待与内部多次请求），须不短于 1 毫秒，默认 15 秒。 */
    public var timeout: Duration = 15.seconds
        set(value) {
            require(value.inWholeMilliseconds > 0) { "timeout 须不短于 1 毫秒，实际为 $value" }
            field = value
        }

    override fun toString(): String = "ZzuClientConfig(timeout=$timeout)"
}

/** 组装 `zzu_*_new` 的配置 JSON：[fields] 是该客户端的必填字段。 */
internal fun nativeConfig(
    configure: ZzuClientConfig.() -> Unit,
    fields: JsonObjectBuilder.() -> Unit = {},
): JsonObject = buildJsonObject {
    fields()
    put("timeoutMs", ZzuClientConfig().apply(configure).timeout.inWholeMilliseconds)
}

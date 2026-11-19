package com.illustar0.zzu

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 校园服务使用的标准时区（`Asia/Shanghai`，UTC+8）。
 *
 * 用于将 [Schedule][com.illustar0.zzu.eas.Schedule] 课表时刻与 [OnlineDevice.loginTime][com.illustar0.zzu.network.OnlineDevice.loginTime]
 * 等 [Instant][kotlin.time.Instant] 换算为当地时间。
 */
public val ZzuTimeZone: TimeZone = TimeZone.of("Asia/Shanghai")

/** 各客户端及 [discoverPortal][com.illustar0.zzu.network.discoverPortal] 共用的可选配置。 */
public class ZzuClientConfig internal constructor() {
    /**
     * 单次操作端到端的总时限，默认 15 秒。
     *
     * 时限包含等待内部锁、令牌换新锁的排队耗时以及多次 HTTP 往返，超时后抛出 [ZzuException.Timeout]。
     * 至少为 1 毫秒，否则在赋值时抛出 [IllegalArgumentException]。
     */
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

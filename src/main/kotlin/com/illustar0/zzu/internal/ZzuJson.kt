package com.illustar0.zzu.internal

import kotlinx.serialization.json.Json

/** ABI 约定：结果对象可能新增字段，解析时须忽略未知字段。 */
internal val ZzuJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

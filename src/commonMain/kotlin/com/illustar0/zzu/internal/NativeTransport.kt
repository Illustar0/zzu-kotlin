package com.illustar0.zzu.internal

import kotlinx.serialization.json.JsonObject

internal expect fun nativeCasTransport(config: JsonObject): CasTransport

internal expect fun nativePortalTransport(config: JsonObject): ZzuTransport

internal expect fun nativeSelfServiceTransport(config: JsonObject): ZzuTransport

internal expect suspend fun nativeDiscoverPortalBytes(options: JsonObject): ByteArray

internal expect fun nativeRoomIdParse(room: String): String

internal expect fun nativeCheckoutUrl(params: JsonObject): String

internal expect fun nativeTimetableToCalendar(params: JsonObject): String

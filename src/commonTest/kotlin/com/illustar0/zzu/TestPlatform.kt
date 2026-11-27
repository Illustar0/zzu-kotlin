package com.illustar0.zzu

import kotlinx.coroutines.CompletableDeferred

internal expect fun isNativeLibraryAvailable(): Boolean

internal expect fun assumeNativeLibraryAvailable()

internal expect fun unusedLoopbackPort(): Int

internal expect suspend fun withStalledServer(
    block: suspend (url: String, accepted: CompletableDeferred<Unit>) -> Unit,
)

internal expect suspend fun withMockSelfServiceServer(
    queryStarted: CompletableDeferred<Unit>,
    resumeQuery: CompletableDeferred<Unit>,
    block: suspend (url: String) -> Unit,
)

internal const val MOCK_ONLINE_LIST_JSON = """[{
    "brasid": "1", "downFlow": "1024", "hostName": null, "ip": "10.1.2.3",
    "loginTime": "2026-10-31 08:30:45", "mac": "001122334455",
    "sessionId": "sid-1", "terminalType": "PC", "upFlow": "256",
    "useTime": "3661", "userId": 42
}]"""

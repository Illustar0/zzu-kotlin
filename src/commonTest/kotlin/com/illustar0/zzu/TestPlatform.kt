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

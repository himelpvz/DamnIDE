package com.hypex.damnide.core.terminal.runtime

import com.hypex.damnide.core.terminal.pty.NativeTerminalBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class LocalRootfsRuntime(
    private val bridge: NativeTerminalBridge,
) : TerminalRuntime {
    private val allowedRootPrefix = "/data/user/0/com.hypex.damnide/files/"

    override suspend fun start(rootfsPath: String): Result<String> = withContext(Dispatchers.IO) {
        val root = File(rootfsPath)
        if (!root.exists() || !root.isDirectory) {
            return@withContext Result.failure(IllegalArgumentException("Rootfs path does not exist: $rootfsPath"))
        }

        val normalizedPath = root.canonicalPath
        if (!normalizedPath.startsWith(allowedRootPrefix)) {
            return@withContext Result.failure(
                IllegalArgumentException("Rootfs must be inside app internal storage."),
            )
        }
        val started = bridge.startShell(normalizedPath)
        if (!started) {
            val message = bridge.lastError() ?: "Failed to start terminal shell."
            return@withContext Result.failure(IllegalStateException(message))
        }

        Result.success(normalizedPath)
    }

    override suspend fun stop() {
        withContext(Dispatchers.IO) {
            bridge.stopShell()
        }
    }

    override suspend fun write(input: String) {
        withContext(Dispatchers.IO) {
            bridge.writeToShell(input)
        }
    }

    override fun observeOutput(): Flow<String> = bridge.observeOutput()

    override fun resize(cols: Int, rows: Int) {
        bridge.resize(cols, rows)
    }
}

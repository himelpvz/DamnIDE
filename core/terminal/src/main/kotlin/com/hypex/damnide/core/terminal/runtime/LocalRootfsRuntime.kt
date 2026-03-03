package com.hypex.damnide.core.terminal.runtime

import android.content.Context
import com.hypex.damnide.core.terminal.pty.NativeTerminalBridge
import com.hypex.damnide.core.terminal.utils.RootfsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class LocalRootfsRuntime(
    context: Context,
    private val bridge: NativeTerminalBridge,
) : TerminalRuntime {
    private val appContext = context.applicationContext
    private val rootfsManager = RootfsManager(appContext)

    override suspend fun start(rootfsPath: String): Result<String> = withContext(Dispatchers.IO) {
        val root = File(rootfsPath)
        if (!root.exists() || !root.isDirectory) {
            return@withContext Result.failure(IllegalArgumentException("Rootfs path does not exist: $rootfsPath"))
        }

        val normalizedPath = root.canonicalPath
        val internalRoot = appContext.filesDir.canonicalPath
        if (!normalizedPath.startsWith(internalRoot)) {
            return@withContext Result.failure(
                IllegalArgumentException("Rootfs must be inside app internal storage."),
            )
        }

        val (prootBinaryPath, args) = rootfsManager.getProotCommand(normalizedPath).getOrElse {
            return@withContext Result.failure(it)
        }

        val started = bridge.startShell(prootBinaryPath, args)
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

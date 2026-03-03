package com.hypex.damnide.core.terminal.pty

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class NativeTerminalBridge {
    private data class Session(
        val masterFd: Int,
        val childPid: Int,
    )

    private val outputFlow = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 256,
    )
    private var session: Session? = null

    init {
        NativeTerminalBindings.bind(this)
    }

    fun startShell(prootBinaryPath: String, args: List<String>): Boolean {
        val masterFd = NativeTerminalBindings.startShell(prootBinaryPath, args.toTypedArray())
        if (masterFd < 0) return false
        val childPid = NativeTerminalBindings.childPid(masterFd)
        if (childPid <= 0) return false
        session = Session(masterFd = masterFd, childPid = childPid)
        return true
    }

    fun writeToShell(input: String) {
        val active = session ?: return
        NativeTerminalBindings.write(active.masterFd, input)
    }

    fun observeOutput(): Flow<String> = outputFlow.asSharedFlow()

    fun resize(cols: Int, rows: Int) {
        val active = session ?: return
        NativeTerminalBindings.resize(active.masterFd, cols, rows)
    }

    fun stopShell() {
        val active = session ?: return
        NativeTerminalBindings.stop(active.childPid)
        session = null
    }

    fun lastError(): String? = NativeTerminalBindings.lastError()

    internal fun onNativeOutput(output: String) {
        outputFlow.tryEmit(output)
    }

    internal fun onNativeError(error: String) {
        outputFlow.tryEmit("\u001B[31m$error\u001B[0m\n")
    }

    internal fun onNativeExit(exitCode: Int) {
        outputFlow.tryEmit("\n\u001B[33mProcess exited with code $exitCode\u001B[0m\n")
    }
}

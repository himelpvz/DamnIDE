package com.hypex.damnide.core.terminal.pty

internal object NativeTerminalBindings {
    private const val NATIVE_UNAVAILABLE_MESSAGE =
        "Native terminal library unavailable in this environment."

    private val isNativeLoaded: Boolean = runCatching {
        System.loadLibrary("terminal_native")
    }.isSuccess

    private external fun nativeStartShell(rootfsPath: String): Int
    private external fun nativeWrite(masterFd: Int, input: String)
    private external fun nativeResize(masterFd: Int, cols: Int, rows: Int)
    private external fun nativeStop(childPid: Int)
    private external fun nativeChildPid(masterFd: Int): Int
    private external fun nativeLastError(): String?

    fun startShell(rootfsPath: String): Int {
        if (!isNativeLoaded) return -1
        return nativeStartShell(rootfsPath)
    }

    fun write(masterFd: Int, input: String) {
        if (!isNativeLoaded) return
        nativeWrite(masterFd, input)
    }

    fun resize(masterFd: Int, cols: Int, rows: Int) {
        if (!isNativeLoaded) return
        nativeResize(masterFd, cols, rows)
    }

    fun stop(childPid: Int) {
        if (!isNativeLoaded) return
        nativeStop(childPid)
    }

    fun childPid(masterFd: Int): Int {
        if (!isNativeLoaded) return -1
        return nativeChildPid(masterFd)
    }

    fun lastError(): String? {
        if (!isNativeLoaded) return NATIVE_UNAVAILABLE_MESSAGE
        return nativeLastError()
    }

    @Volatile
    private var bridge: NativeTerminalBridge? = null

    fun bind(terminalBridge: NativeTerminalBridge) {
        bridge = terminalBridge
    }

    fun unbind(terminalBridge: NativeTerminalBridge) {
        if (bridge === terminalBridge) {
            bridge = null
        }
    }

    @JvmStatic
    fun dispatchOutput(output: String) {
        bridge?.onNativeOutput(output)
    }

    @JvmStatic
    fun dispatchError(error: String) {
        bridge?.onNativeError(error)
    }

    @JvmStatic
    fun dispatchExit(exitCode: Int) {
        bridge?.onNativeExit(exitCode)
    }
}

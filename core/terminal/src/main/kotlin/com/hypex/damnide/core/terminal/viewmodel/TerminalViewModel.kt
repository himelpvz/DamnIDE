package com.hypex.damnide.core.terminal.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hypex.damnide.core.terminal.common.AnsiParser
import com.hypex.damnide.core.terminal.model.TerminalState
import com.hypex.damnide.core.terminal.pty.NativeTerminalBridge
import com.hypex.damnide.core.terminal.runtime.LocalRootfsRuntime
import com.hypex.damnide.core.terminal.runtime.TerminalRuntime
import com.hypex.damnide.core.terminal.utils.RootfsResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

class TerminalViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val runtime: TerminalRuntime = LocalRootfsRuntime(application, NativeTerminalBridge())
    private val parser: AnsiParser = AnsiParser()

    private val _state = MutableStateFlow(TerminalState())
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    private var outputJob: Job? = null
    private val installRootfsDir: File
        get() = File(getApplication<Application>().filesDir, "home")

    fun useInstalledRootfs() {
        viewModelScope.launch(Dispatchers.IO) {
            val resolvedPath = RootfsResolver.validateDirectoryPath(installRootfsDir.absolutePath).getOrNull()
            if (resolvedPath == null) {
                _state.update {
                    it.copy(errorMessage = "Installed rootfs not found at ${installRootfsDir.absolutePath}")
                }
                return@launch
            }

            _state.update {
                it.copy(
                    selectedRootfsPath = resolvedPath,
                    errorMessage = null,
                )
            }
        }
    }

    fun onRootfsArchiveSelected(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            kotlin.runCatching {
                RootfsResolver.persistPermission(getApplication<Application>().contentResolver, uri)
                RootfsResolver.installArchiveToDirectory(
                    context = getApplication(),
                    sourceUri = uri,
                    targetRootfsDir = installRootfsDir,
                ).getOrThrow()
            }.onSuccess { installedPath ->
                _state.update {
                    it.copy(
                        selectedRootfsUri = uri,
                        selectedRootfsPath = installedPath,
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(errorMessage = error.message ?: "Rootfs install failed.")
                }
            }
        }
    }

    fun startSession() {
        if (_state.value.isRunning) return
        val path = _state.value.selectedRootfsPath ?: run {
            _state.update { it.copy(errorMessage = "Select or configure a valid rootfs directory first.") }
            return
        }

        outputJob?.cancel()
        outputJob = viewModelScope.launch {
            runtime.observeOutput().collect { chunk ->
                val parsed = parser.append(chunk)
                _state.update { current -> current.copy(lines = parsed) }
            }
        }

        viewModelScope.launch {
            val result = runtime.start(path)
            result.onSuccess { resolvedPath ->
                _state.update {
                    it.copy(
                        selectedRootfsPath = resolvedPath,
                        isRunning = true,
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isRunning = false,
                        errorMessage = error.message ?: "Unable to start shell.",
                    )
                }
            }
        }
    }

    fun stopSession() {
        viewModelScope.launch {
            runtime.stop()
            _state.update { it.copy(isRunning = false) }
        }
    }

    fun sendInput(input: String) {
        if (!_state.value.isRunning) return
        viewModelScope.launch {
            runtime.write(input)
        }
    }

    fun resize(cols: Int, rows: Int) {
        runtime.resize(cols, rows)
    }

    override fun onCleared() {
        outputJob?.cancel()
        runBlocking {
            runtime.stop()
        }
        super.onCleared()
    }
}

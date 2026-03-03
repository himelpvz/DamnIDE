package com.hypex.damnide.core.terminal.runtime

import kotlinx.coroutines.flow.Flow

interface TerminalRuntime {
    suspend fun start(rootfsPath: String): Result<String>
    suspend fun stop()
    suspend fun write(input: String)
    fun observeOutput(): Flow<String>
    fun resize(cols: Int, rows: Int)
}

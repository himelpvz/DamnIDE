package com.hypex.damnide.core.terminal.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hypex.damnide.core.terminal.viewmodel.TerminalViewModel

@Composable
fun TerminalScreen() {
    val vm: TerminalViewModel = viewModel()
    val context = LocalContext.current

    val state by vm.state.collectAsState()
    var inputBuffer by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    val rootfsPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(vm::onRootfsArchiveSelected)
    }

    LaunchedEffect(Unit) {
        vm.useInstalledRootfs()
    }

    LaunchedEffect(state.lines.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        color = Color(0xFF1E1E1E),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(Color(0xFF252526))
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = state.sessionName,
                        color = Color(0xFFD4D4D4),
                        fontSize = 14.sp,
                    )
                    Text(
                        text = state.selectedRootfsPath
                            ?: "${context.filesDir.absolutePath}/home",
                        color = Color(0xFF9DA0A8),
                        fontSize = 11.sp,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { rootfsPicker.launch(arrayOf("*/*")) }) {
                        Text("Select Rootfs")
                    }
                    if (state.isRunning) {
                        Button(onClick = vm::stopSession) {
                            Text("Stop")
                        }
                    } else {
                        Button(onClick = vm::startSession) {
                            Text("Start")
                        }
                    }
                }
            }

            TerminalRenderer(
                lines = state.lines,
                cursorVisible = state.cursorVisible && state.isRunning,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(12.dp),
            )

            state.errorMessage?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp),
                    fontSize = 12.sp,
                )
            }

            BasicTextField(
                value = inputBuffer,
                onValueChange = { newValue ->
                    val delta = newValue.removePrefix(inputBuffer)
                    if (delta.isNotEmpty()) {
                        vm.sendInput(delta)
                    }
                    inputBuffer = newValue
                    if (inputBuffer.length > 256) {
                        inputBuffer = ""
                    }
                },
                textStyle = TextStyle(
                    color = Color.Transparent,
                    fontSize = 1.sp,
                ),
                modifier = Modifier
                    .size(1.dp)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter -> {
                                vm.sendInput("\n")
                                true
                            }
                            Key.Backspace -> {
                                vm.sendInput("\b")
                                true
                            }
                            Key.Tab -> {
                                vm.sendInput("\t")
                                true
                            }
                            else -> false
                        }
                    },
            )

            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
                vm.resize(cols = 120, rows = 40)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .background(Color(0xFF252526))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = if (state.isRunning) "Shell active" else "Shell idle",
                    color = Color(0xFF9DA0A8),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

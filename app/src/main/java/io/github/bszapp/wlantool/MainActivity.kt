package io.github.bszapp.wlantool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bszapp.wlantool.terminal.LaunchMode
import io.github.bszapp.wlantool.ui.theme.WlanToolTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WlanToolTheme {
                val vm: MainViewModel = viewModel()
                val state by vm.uiState.collectAsStateWithLifecycle()
                WlanToolApp(
                    state = state,
                    onLaunchProot = vm::launchProot,
                    onLaunchChroot = vm::launchChroot,
                    onInputChanged = vm::updateInput,
                    onSendInput = vm::sendInput,
                    onCloseTerminal = vm::closeTerminal,
                    onDismissError = vm::dismissError,
                )
            }
        }
    }
}

@Composable
private fun WlanToolApp(
    state: MainUiState,
    onLaunchProot: () -> Unit,
    onLaunchChroot: () -> Unit,
    onInputChanged: (String) -> Unit,
    onSendInput: () -> Unit,
    onCloseTerminal: () -> Unit,
    onDismissError: () -> Unit,
) {
    when (state.page) {
        AppPage.HOME -> HomeScreen(
            onLaunchProot = onLaunchProot,
            onLaunchChroot = onLaunchChroot,
        )
        AppPage.RUNNING -> TerminalScreen(
            state = state,
            onInputChanged = onInputChanged,
            onSendInput = onSendInput,
            onCloseTerminal = onCloseTerminal,
        )
    }

    if (state.isLaunching) {
        LaunchingDialog(message = state.launchMessage)
    }

    state.errorDetail?.let { detail ->
        ErrorDetailDialog(
            detail = detail,
            onDismiss = onDismissError,
        )
    }
}

@Composable
private fun ErrorDetailDialog(
    detail: String,
    onDismiss: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("启动失败") },
        text = {
            BoxWithConstraints {
                SelectionContainer {
                    Text(
                        text = detail,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxHeight * 0.75f)
                            .verticalScroll(scrollState),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(detail))
                }
            ) {
                Text("复制详情")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    onLaunchProot: () -> Unit,
    onLaunchChroot: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WlanTool") },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .safeDrawingPadding()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "选择容器运行方式",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "应用会先准备 rootfs 和运行环境，再进入可持续交互的终端页面。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(24.dp))
            LaunchCard(
                title = "运行 Proot",
                subtitle = "无需 Root，直接进入容器终端",
                icon = {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                    )
                },
                onClick = onLaunchProot,
            )
            Spacer(modifier = Modifier.height(16.dp))
            LaunchCard(
                title = "运行 Chroot",
                subtitle = "需要 su 获取最高权限后进入容器终端",
                icon = {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                    )
                },
                onClick = onLaunchChroot,
            )
        }
    }
}

@Composable
private fun LaunchCard(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = RoundedCornerShape(28.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(18.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp, end = 12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = null,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalScreen(
    state: MainUiState,
    onInputChanged: (String) -> Unit,
    onSendInput: () -> Unit,
    onCloseTerminal: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()
    LaunchedEffect(state.terminalOutput) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = buildString {
                            append("运行中")
                            state.activeMode?.let {
                                append(" · ")
                                append(it.displayName)
                            }
                        }
                    )
                },
                actions = {
                    TextButton(
                        onClick = {
                            clipboardManager.setText(
                                AnnotatedString(
                                    if (state.terminalOutput.isBlank()) {
                                        "终端尚无输出"
                                    } else {
                                        state.terminalOutput
                                    }
                                )
                            )
                        }
                    ) {
                        Text("复制")
                    }
                    FilledIconButton(onClick = onCloseTerminal) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭终端",
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(12.dp),
                ) {
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        OutlinedTextField(
                            value = state.currentInput,
                            onValueChange = onInputChanged,
                            modifier = Modifier.weight(1f),
                            label = { Text("输入命令") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = { onSendInput() },
                            ),
                        )
                        Spacer(modifier = Modifier.size(12.dp))
                        FilledIconButton(
                            onClick = onSendInput,
                            modifier = Modifier.size(56.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowForward,
                                contentDescription = "发送",
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(12.dp),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF0F172A),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(scrollState),
                ) {
                    SelectionContainer {
                        Text(
                            text = if (state.terminalOutput.isBlank()) {
                                "终端已启动，等待输出…"
                            } else {
                                state.terminalOutput
                            },
                            color = Color(0xFFE2E8F0),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LaunchingDialog(message: String) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text("启动中") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                )
                Spacer(modifier = Modifier.size(16.dp))
                Text(message)
            }
        },
    )
}

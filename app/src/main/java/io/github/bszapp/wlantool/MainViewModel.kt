package io.github.bszapp.wlantool

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bszapp.wlantool.terminal.LaunchMode
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val terminalManager = (application as WlanToolApplication).terminalSessionManager

    private val currentPage = MutableStateFlow(AppPage.HOME)
    private val currentInput = MutableStateFlow("")
    private val isLaunching = MutableStateFlow(false)
    private val launchMessage = MutableStateFlow("")
    private val errorDetail = MutableStateFlow<String?>(null)

    private val launchUiState = combine(
        isLaunching,
        launchMessage,
        errorDetail,
    ) { launching, launchStep, error ->
        LaunchUiState(
            isLaunching = launching,
            launchMessage = launchStep,
            errorDetail = error,
        )
    }

    private val terminalUiState = combine(
        terminalManager.output,
        terminalManager.activeMode,
    ) { output, activeMode ->
        TerminalUiState(
            terminalOutput = output,
            activeMode = activeMode,
        )
    }

    val uiState: StateFlow<MainUiState> = combine(
        currentPage,
        currentInput,
        launchUiState,
        terminalUiState,
    ) { page, input, launchState, terminalState ->
        MainUiState(
            page = page,
            currentInput = input,
            isLaunching = launchState.isLaunching,
            launchMessage = launchState.launchMessage,
            errorDetail = launchState.errorDetail,
            terminalOutput = terminalState.terminalOutput,
            activeMode = terminalState.activeMode,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(),
    )

    fun launchProot() {
        launchSession(LaunchMode.PROOT)
    }

    fun launchChroot() {
        launchSession(LaunchMode.CHROOT)
    }

    fun updateInput(value: String) {
        currentInput.value = value
    }

    fun sendInput() {
        val text = currentInput.value.trim()
        if (text.isEmpty()) {
            return
        }
        viewModelScope.launch {
            terminalManager.send(text)
            currentInput.value = ""
        }
    }

    fun closeTerminal() {
        viewModelScope.launch {
            terminalManager.stop()
            currentPage.value = AppPage.HOME
        }
    }

    fun dismissError() {
        errorDetail.value = null
    }

    private fun launchSession(mode: LaunchMode) {
        viewModelScope.launch {
            isLaunching.value = true
            launchMessage.value = "正在准备启动…"
            errorDetail.value = null
            try {
                terminalManager.start(mode) { step ->
                    launchMessage.value = step
                }
                currentPage.value = AppPage.RUNNING
            } catch (t: Throwable) {
                errorDetail.value = buildDetailedError(
                    mode = mode,
                    stage = launchMessage.value,
                    throwable = t,
                )
            } finally {
                isLaunching.value = false
            }
        }
    }

    private fun buildDetailedError(
        mode: LaunchMode,
        stage: String,
        throwable: Throwable,
    ): String {
        val writer = StringWriter()
        PrintWriter(writer).use { printWriter ->
            printWriter.println("启动模式: ${mode.displayName}")
            if (stage.isNotBlank()) {
                printWriter.println("失败阶段: $stage")
            }
            printWriter.println("异常类型: ${throwable::class.java.name}")
            printWriter.println(
                "异常消息: ${throwable.message ?: "<empty>"}"
            )

            var cause = throwable.cause
            var index = 1
            while (cause != null) {
                printWriter.println(
                    "原因[$index]: ${cause::class.java.name}: ${cause.message ?: "<empty>"}"
                )
                cause = cause.cause
                index += 1
            }

            printWriter.println()
            printWriter.println("堆栈:")
            throwable.printStackTrace(printWriter)
        }
        return writer.toString().trimEnd()
    }
}

enum class AppPage {
    HOME,
    RUNNING,
}

data class MainUiState(
    val page: AppPage = AppPage.HOME,
    val currentInput: String = "",
    val isLaunching: Boolean = false,
    val launchMessage: String = "",
    val errorDetail: String? = null,
    val terminalOutput: String = "",
    val activeMode: LaunchMode? = null,
)

private data class LaunchUiState(
    val isLaunching: Boolean,
    val launchMessage: String,
    val errorDetail: String?,
)

private data class TerminalUiState(
    val terminalOutput: String,
    val activeMode: LaunchMode?,
)

package io.github.bszapp.wlantool.terminal

import android.content.Context
import android.os.ParcelFileDescriptor
import io.github.bszapp.wlantool.bridge.RftoolBridge
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TerminalSessionManager(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()
    private val extractionMutex = Mutex()

    private val _output = MutableStateFlow("")
    private val _activeMode = MutableStateFlow<LaunchMode?>(null)
    private val terminalSanitizer = TerminalSanitizer()

    val output: StateFlow<String> = _output.asStateFlow()
    val activeMode: StateFlow<LaunchMode?> = _activeMode.asStateFlow()

    private var activeSession: ActiveSession? = null

    suspend fun start(
        mode: LaunchMode,
        onProgress: suspend (String) -> Unit,
    ) {
        sessionMutex.withLock {
            stopLocked()
            _output.value = ""
            terminalSanitizer.reset()

            onProgress("正在准备容器资源…")
            val rootfsDir = ensureRootfs(onProgress)

            if (mode == LaunchMode.CHROOT) {
                onProgress("正在检查 Root 能力…")
                ensureSuAvailable()
            }

            val prootBinary = if (mode == LaunchMode.PROOT) {
                onProgress("正在定位 Proot 原生库…")
                resolveProotBinary()
            } else {
                null
            }
            val prootLoader = if (mode == LaunchMode.PROOT) {
                resolveProotLoader()
            } else {
                null
            }
            val runtimeDir = File(context.noBackupFilesDir, "rftool-runtime").apply {
                mkdirs()
            }
            onProgress(
                when (mode) {
                    LaunchMode.PROOT -> "正在启动 Proot 容器…"
                    LaunchMode.CHROOT -> "正在申请 Root 并启动 Chroot…"
                }
            )

            val result = RftoolBridge.startSession(
                mode = mode.nativeMode,
                rootfsPath = rootfsDir.absolutePath,
                runtimePath = runtimeDir.absolutePath,
                prootPath = prootBinary?.absolutePath.orEmpty(),
                prootLoader = prootLoader?.absolutePath.orEmpty(),
            )
            check(result.size >= 3) { "native result is malformed" }

            val session = ActiveSession(
                pid = result[0],
                readPfd = ParcelFileDescriptor.adoptFd(result[1].toInt()),
                writePfd = ParcelFileDescriptor.adoptFd(result[2].toInt()),
            )
            activeSession = session
            _activeMode.value = mode
            session.readerJob = scope.launch {
                readLoop(session)
            }
        }
    }

    suspend fun send(command: String) {
        if (command.isBlank()) {
            return
        }
        sessionMutex.withLock {
            val session = activeSession ?: return
            session.writeStream.write(command.toByteArray(Charsets.UTF_8))
            session.writeStream.write('\n'.code)
            session.writeStream.flush()
        }
    }

    suspend fun stop() {
        sessionMutex.withLock {
            stopLocked()
        }
    }

    private suspend fun stopLocked() {
        val session = activeSession ?: return
        try {
            session.writeStream.write("exit\n".toByteArray(Charsets.UTF_8))
            session.writeStream.flush()
        } catch (_: IOException) {
        }
        delay(250)
        RftoolBridge.terminateProcess(session.pid)
        session.close()
        activeSession = null
        _activeMode.value = null
    }

    private suspend fun ensureRootfs(onProgress: suspend (String) -> Unit): File {
        return extractionMutex.withLock {
            val rootfsDir = File(context.filesDir, "rootfs")
            if (File(rootfsDir, "bin/sh").exists()) {
                return@withLock rootfsDir
            }

            onProgress("正在复制 rootfs 资源…")
            val archiveFile = withContext(Dispatchers.IO) {
                copyBundledRootfsAsset()
            }

            onProgress("正在解压 rootfs，请稍候…")
            if (rootfsDir.exists()) {
                rootfsDir.deleteRecursively()
            }
            rootfsDir.mkdirs()
            withContext(Dispatchers.IO) {
                try {
                    RftoolBridge.extractRootfs(
                        archivePath = archiveFile.absolutePath,
                        destinationPath = rootfsDir.absolutePath,
                    )
                } catch (t: Throwable) {
                    throw IOException(
                        "解压 rootfs 失败: archive=${archiveFile.absolutePath}, destination=${rootfsDir.absolutePath}",
                        t,
                    )
                }
            }
            rootfsDir
        }
    }

    private fun copyBundledRootfsAsset(): File {
        val availableAssets = runCatching {
            context.assets.list("")?.sorted().orEmpty()
        }.getOrElse { emptyList() }
        val candidateAssets = listOf("rootfs.tar.gz", "rootfs.tar")
        val assetName = candidateAssets.firstOrNull { candidate ->
            availableAssets.contains(candidate)
        } ?: candidateAssets.firstOrNull { candidate ->
            runCatching {
                context.assets.open(candidate).close()
                true
            }.getOrDefault(false)
        } ?: throw IOException(
            buildString {
                append("未找到 rootfs 资源: tried=")
                append(candidateAssets.joinToString(","))
                append(", available=")
                append(
                    if (availableAssets.isEmpty()) {
                        "<empty>"
                    } else {
                        availableAssets.joinToString(",")
                    }
                )
            }
        )

        val archiveFile = File(context.noBackupFilesDir, assetName).apply {
            parentFile?.mkdirs()
        }
        try {
            context.assets.open(assetName).use { input ->
                archiveFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (t: Throwable) {
            throw IOException(
                "复制 rootfs 资源失败: asset=$assetName, target=${archiveFile.absolutePath}, available=${
                    if (availableAssets.isEmpty()) "<empty>" else availableAssets.joinToString(",")
                }",
                t,
            )
        }
        return archiveFile
    }

    private suspend fun ensureSuAvailable() {
        withContext(Dispatchers.IO) {
            val process = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val exitCode = withTimeoutOrNull(5_000) {
                process.waitFor()
            } ?: run {
                process.destroyForcibly()
                throw IllegalStateException("获取 Root 超时或被拒绝")
            }
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            if (exitCode != 0) {
                throw IllegalStateException(
                    if (output.isBlank()) {
                        "无法执行 su"
                    } else {
                        "无法执行 su：$output"
                    }
                )
            }
        }
    }

    private fun resolveProotBinary(): File {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val prootBinary = File(nativeDir, "libproot.so")
        if (prootBinary.isFile) {
            return prootBinary
        }

        val availableFiles = nativeDir.list()?.sorted().orEmpty()
        throw IOException(
            buildString {
                append("未找到 Proot 原生库: expected=")
                append(prootBinary.absolutePath)
                append(", nativeLibraryDir=")
                append(nativeDir.absolutePath)
                append(", available=")
                append(
                    if (availableFiles.isEmpty()) {
                        "<empty>"
                    } else {
                        availableFiles.joinToString(",")
                    }
                )
            }
        )
    }

    private fun resolveProotLoader(): File {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val loaderBinary = File(nativeDir, "libproot-loader.so")
        if (loaderBinary.isFile) {
            return loaderBinary
        }

        val availableFiles = nativeDir.list()?.sorted().orEmpty()
        throw IOException(
            buildString {
                append("未找到 Proot loader 原生库: expected=")
                append(loaderBinary.absolutePath)
                append(", nativeLibraryDir=")
                append(nativeDir.absolutePath)
                append(", available=")
                append(
                    if (availableFiles.isEmpty()) {
                        "<empty>"
                    } else {
                        availableFiles.joinToString(",")
                    }
                )
            }
        )
    }

    private fun readLoop(session: ActiveSession) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var endNote = "\n\n[终端会话已结束]\n"
        try {
            while (true) {
                val count = session.readStream.read(buffer)
                if (count <= 0) {
                    break
                }
                val chunk = String(buffer, 0, count, Charsets.UTF_8)
                val sanitized = terminalSanitizer.consume(chunk)
                if (sanitized.displayText.isNotEmpty()) {
                    appendOutput(sanitized.displayText)
                }
                if (sanitized.replyText.isNotEmpty()) {
                    try {
                        session.writeStream.write(sanitized.replyText.toByteArray(Charsets.UTF_8))
                        session.writeStream.flush()
                    } catch (_: IOException) {
                    }
                }
            }
        } catch (e: IOException) {
            if (!e.message.isNullOrBlank()) {
                endNote = "\n\n[终端会话异常结束: ${e.message}]\n"
            }
        } finally {
            scope.launch {
                sessionMutex.withLock {
                    if (activeSession === session) {
                        appendOutput(endNote)
                        session.close()
                        activeSession = null
                        _activeMode.value = null
                    }
                }
            }
        }
    }

    private fun appendOutput(chunk: String) {
        val combined = buildString(_output.value.length + chunk.length) {
            append(_output.value)
            append(chunk)
        }
        _output.value = if (combined.length > MAX_OUTPUT_CHARS) {
            combined.takeLast(MAX_OUTPUT_CHARS)
        } else {
            combined
        }
    }

    private data class ActiveSession(
        val pid: Long,
        val readPfd: ParcelFileDescriptor,
        val writePfd: ParcelFileDescriptor,
        var readerJob: kotlinx.coroutines.Job? = null,
    ) {
        val readStream = ParcelFileDescriptor.AutoCloseInputStream(readPfd)
        val writeStream = ParcelFileDescriptor.AutoCloseOutputStream(writePfd)

        fun close() {
            readerJob?.cancel()
            try {
                readStream.close()
            } catch (_: IOException) {
            }
            try {
                writeStream.close()
            } catch (_: IOException) {
            }
            try {
                readPfd.close()
            } catch (_: IOException) {
            }
            try {
                writePfd.close()
            } catch (_: IOException) {
            }
        }
    }

    private companion object {
        private const val MAX_OUTPUT_CHARS = 200_000
    }

    private data class SanitizedChunk(
        val displayText: String,
        val replyText: String,
    )

    private class TerminalSanitizer {
        private enum class Mode {
            TEXT,
            ESC,
            CSI,
            OSC,
            OSC_ESC,
        }

        private var mode = Mode.TEXT
        private val csiBuffer = StringBuilder()

        fun reset() {
            mode = Mode.TEXT
            csiBuffer.setLength(0)
        }

        fun consume(input: String): SanitizedChunk {
            val display = StringBuilder(input.length)
            val reply = StringBuilder()

            input.forEach { ch ->
                when (mode) {
                    Mode.TEXT -> when (ch) {
                        '\u001B' -> mode = Mode.ESC
                        '\r' -> {
                            if (display.lastOrNull() != '\n') {
                                display.append('\n')
                            }
                        }
                        '\b' -> {
                            if (display.isNotEmpty()) {
                                display.deleteCharAt(display.lastIndex)
                            }
                        }
                        '\n', '\t' -> display.append(ch)
                        else -> {
                            if (ch >= ' ') {
                                display.append(ch)
                            }
                        }
                    }

                    Mode.ESC -> when (ch) {
                        '[' -> {
                            mode = Mode.CSI
                            csiBuffer.setLength(0)
                            csiBuffer.append('[')
                        }
                        ']' -> mode = Mode.OSC
                        else -> mode = Mode.TEXT
                    }

                    Mode.CSI -> {
                        csiBuffer.append(ch)
                        if (ch in '@'..'~') {
                            if (csiBuffer.toString() == "[6n") {
                                reply.append("\u001B[1;1R")
                            }
                            csiBuffer.setLength(0)
                            mode = Mode.TEXT
                        }
                    }

                    Mode.OSC -> {
                        mode = if (ch == '\u0007') {
                            Mode.TEXT
                        } else if (ch == '\u001B') {
                            Mode.OSC_ESC
                        } else {
                            Mode.OSC
                        }
                    }

                    Mode.OSC_ESC -> {
                        mode = Mode.TEXT
                    }
                }
            }

            return SanitizedChunk(
                displayText = display.toString(),
                replyText = reply.toString(),
            )
        }
    }
}

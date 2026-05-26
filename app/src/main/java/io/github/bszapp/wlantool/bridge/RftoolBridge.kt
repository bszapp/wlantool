package io.github.bszapp.wlantool.bridge

object RftoolBridge {
    init {
        System.loadLibrary("rftool")
    }

    external fun extractRootfs(archivePath: String, destinationPath: String)

    external fun startSession(
        mode: Int,
        rootfsPath: String,
        runtimePath: String,
        prootPath: String,
        prootLoader: String,
    ): LongArray

    external fun terminateProcess(pid: Long)
}

package io.github.bszapp.wlantool.terminal

enum class LaunchMode(
    val nativeMode: Int,
    val displayName: String,
) {
    PROOT(nativeMode = 1, displayName = "Proot"),
    CHROOT(nativeMode = 2, displayName = "Chroot"),
}

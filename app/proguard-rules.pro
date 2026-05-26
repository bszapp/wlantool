# Keep JNI entry points stable after R8 obfuscation.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class io.github.bszapp.wlantool.bridge.RftoolBridge { *; }

# The app starts these native binaries by file name from nativeLibraryDir.
-keep class io.github.bszapp.wlantool.terminal.** { *; }

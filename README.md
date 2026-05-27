# WlanTool

Android app that packages a small Alpine-based WLAN tool environment and starts it through either `proot` or `chroot`.

## Runtime layout

- `rftoolbuilder/` downloads sources and assembles `rootfs.tar.gz`
- `app/src/main/cpp/` builds `librftool.so`, `libproot.so`, and `libproot-loader.so` from source through CMake
- `app/src/main/assets/rootfs.tar.gz` is generated, ignored, and copied into the APK at build time

## Host requirements

- JDK 17+
- Gradle 9+
- Android SDK command-line tools
- Android NDK `27.2.12479018`
- Android CMake `3.22.1`
- `curl`, `make`, `file`, `unzip`

## From-scratch build

```sh
git clean -fdX
sh rftoolbuilder/downloads.sh
sh rftoolbuilder/build.sh
gradle --no-daemon :app:assembleRelease
```

The second step downloads all source archives needed by both the rootfs packer and the Android native build.

## Install on device

```sh
sudo pm install -r app/build/outputs/apk/release/app-release.apk
```

If your local shell does not provide `sudo`, configure your local environment accordingly instead of changing the source tree.

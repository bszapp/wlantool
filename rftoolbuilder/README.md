# rftoolbuilder

This directory now has one job only: assemble `rootfs.tar.gz`.

## Scope

- `downloads.sh` fetches the source archives and package files required by the project
- `build.sh` unpacks the Alpine base, layers the extra packages, injects `src/wlantool/`, and writes `build/out/rootfs.tar.gz`
- Android native binaries are no longer produced here

## Inputs

- Alpine `minirootfs`
- Alpine package files for Python, `iw`, `wpa_supplicant`, and their runtime dependencies
- `src/wlantool/` scripts that are copied into `/wlantool` inside the root filesystem

## Output

```text
build/out/rootfs.tar.gz
```

The build script also syncs the generated archive into:

```text
app/src/main/assets/rootfs.tar.gz
```

That file is ignored by Git and must be recreated from source inputs whenever the project is rebuilt.

## Rebuild flow

```sh
git clean -fdX
sh rftoolbuilder/downloads.sh
sh rftoolbuilder/build.sh
gradle --no-daemon :app:assembleRelease
```

## Native runtime

`librftool.so`, `libproot.so`, and `libproot-loader.so` are now built from source in `app/src/main/cpp/` by the Android Gradle/CMake pipeline.

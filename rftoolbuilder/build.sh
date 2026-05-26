#!/bin/sh
set -eu
set -x

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD_DIR=$ROOT_DIR/build
OUT_DIR=$BUILD_DIR/out
SRC_DIR=$BUILD_DIR/src
DIST_DIR=$ROOT_DIR/distfiles
MUSL_PREFIX=$BUILD_DIR/musl
TALLOC_PREFIX=$BUILD_DIR/talloc
ROOTFS_STAGE=$BUILD_DIR/rootfs-stage
PROOT_BLOB_DIR=$BUILD_DIR/blob
ROOTFS_ARCHIVE=$OUT_DIR/rootfs.tar.gz
RFTOOL_SO=$OUT_DIR/librftool.so
PROOT_SO=$OUT_DIR/libproot.so
PROOT_LOADER_SO=$OUT_DIR/libproot-loader.so
APP_ASSETS_DIR=$ROOT_DIR/../app/src/main/assets
APP_JNI_DIR=$ROOT_DIR/../app/src/main/jniLibs/arm64-v8a
TERMUX_PREFIX=${PREFIX:-/data/data/com.termux/files/usr}
TARGET=aarch64-linux-musl
ANDROID_TARGET=aarch64-linux-android24
JAVA_HOME_DIR=${JAVA_HOME:-/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk}
JNI_INCLUDE_DIR=$JAVA_HOME_DIR/include
JNI_PLATFORM_INCLUDE_DIR=$JNI_INCLUDE_DIR/linux

die() {
  echo "build.sh: $*" >&2
  exit 1
}

need_tool() {
  command -v "$1" >/dev/null 2>&1 || die "missing tool: $1"
}

pick_tool() {
  for tool in "$@"; do
    if command -v "$tool" >/dev/null 2>&1; then
      command -v "$tool"
      return 0
    fi
  done
  return 1
}

[ "$(uname -m)" = "aarch64" ] || die "this bundle is prepared for aarch64 only"

HOST_CC=$(pick_tool clang cc) || die "need clang/cc from Termux"
AR_TOOL=$(pick_tool llvm-ar ar) || die "need llvm-ar or ar"
RANLIB_TOOL=$(pick_tool llvm-ranlib ranlib) || die "need llvm-ranlib or ranlib"
STRIP_TOOL=$(pick_tool llvm-strip strip) || die "need llvm-strip or strip"
OBJCOPY_TOOL=$(pick_tool llvm-objcopy objcopy) || die "need llvm-objcopy or objcopy"
OBJDUMP_TOOL=$(pick_tool llvm-objdump objdump) || die "need llvm-objdump or objdump"
MAKE_TOOL=$(pick_tool make gmake) || die "need make"

need_tool tar
need_tool gzip
need_tool sed
need_tool awk
need_tool grep
need_tool find
need_tool chmod
need_tool cp
need_tool ln
need_tool rm
need_tool mkdir

JOBS=$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 1)
CLANG_RT_BUILTINS=$("$HOST_CC" --target="$TARGET" --rtlib=compiler-rt --print-libgcc-file-name)
if [ ! -f "$CLANG_RT_BUILTINS" ]; then
  CLANG_RT_BUILTINS=$(find "$TERMUX_PREFIX/lib/clang" -name 'libclang_rt.builtins-aarch64-android.a' | head -n 1)
fi
[ -n "$CLANG_RT_BUILTINS" ] && [ -f "$CLANG_RT_BUILTINS" ] || die "could not locate compiler-rt builtins for $TARGET"
KERNEL_UAPI=
for candidate in "$TERMUX_PREFIX"/include/*-linux-android; do
  if [ -d "$candidate" ] && [ -f "$candidate/asm/unistd.h" ]; then
    KERNEL_UAPI=$candidate
    break
  fi
done
[ -n "$KERNEL_UAPI" ] || die "could not locate Termux target sysroot headers"
[ -f "$JNI_INCLUDE_DIR/jni.h" ] || die "could not locate jni.h under $JNI_INCLUDE_DIR"
[ -d "$JNI_PLATFORM_INCLUDE_DIR" ] || die "could not locate JNI platform headers under $JNI_PLATFORM_INCLUDE_DIR"

for required in \
  "$DIST_DIR/musl-1.2.6.tar.gz" \
  "$DIST_DIR/talloc-2.4.3.tar.gz" \
  "$DIST_DIR/zlib-1.3.1.tar.gz" \
  "$DIST_DIR/proot-termux-58aad2cb1c36ea6af7b32d76ccd5bf8d0a967939.tar.gz" \
  "$DIST_DIR/alpine/alpine-minirootfs-3.23.4-aarch64.tar.gz" \
  "$DIST_DIR/alpine/dbus-libs-1.16.2-r1.apk" \
  "$DIST_DIR/alpine/python3-3.12.13-r0.apk" \
  "$DIST_DIR/alpine/py3-wcwidth-0.2.13-r1.apk" \
  "$DIST_DIR/alpine/libnl3-3.11.0-r0.apk" \
  "$DIST_DIR/alpine/iw-6.17-r0.apk" \
  "$DIST_DIR/alpine/pcsc-lite-libs-2.4.0-r1.apk" \
  "$DIST_DIR/alpine/wpa_supplicant-2.11-r3.apk"; do
  [ -f "$required" ] || die "missing distfile: $required"
done

rm -rf "$BUILD_DIR"
mkdir -p "$OUT_DIR" "$SRC_DIR"

tar -xzf "$DIST_DIR/musl-1.2.6.tar.gz" -C "$SRC_DIR"
tar -xzf "$DIST_DIR/talloc-2.4.3.tar.gz" -C "$SRC_DIR"
tar -xzf "$DIST_DIR/zlib-1.3.1.tar.gz" -C "$SRC_DIR"
tar -xzf "$DIST_DIR/proot-termux-58aad2cb1c36ea6af7b32d76ccd5bf8d0a967939.tar.gz" -C "$SRC_DIR"

PROOT_SRC=$SRC_DIR/proot-58aad2cb1c36ea6af7b32d76ccd5bf8d0a967939
TALLOC_SRC=$SRC_DIR/talloc-2.4.3
ZLIB_SRC=$SRC_DIR/zlib-1.3.1

cp "$ROOT_DIR/src/overrides/proot/src/syscall/rlimit.c" \
  "$PROOT_SRC/src/syscall/rlimit.c"
cp "$ROOT_DIR/src/overrides/proot/src/tracee/seccomp.c" \
  "$PROOT_SRC/src/tracee/seccomp.c"
cp "$ROOT_DIR/src/overrides/proot/src/extension/ashmem_memfd/ashmem_memfd.c" \
  "$PROOT_SRC/src/extension/ashmem_memfd/ashmem_memfd.c"
cp "$ROOT_DIR/src/overrides/proot/src/extension/sysvipc/sysvipc_msg.c" \
  "$PROOT_SRC/src/extension/sysvipc/sysvipc_msg.c"
cp "$ROOT_DIR/src/overrides/proot/src/extension/sysvipc/sysvipc_shm.c" \
  "$PROOT_SRC/src/extension/sysvipc/sysvipc_shm.c"

mkdir -p "$BUILD_DIR/musl-build"
cd "$BUILD_DIR/musl-build"
"$SRC_DIR/musl-1.2.6/configure" \
  --prefix="$MUSL_PREFIX" \
  --target="$TARGET" \
  CC="$HOST_CC --target=$TARGET --rtlib=compiler-rt" \
  AR="$AR_TOOL" \
  RANLIB="$RANLIB_TOOL" \
  LIBCC="$CLANG_RT_BUILTINS"
"$MAKE_TOOL" -j"$JOBS"
"$MAKE_TOOL" install

mkdir -p "$MUSL_PREFIX/include/sys" "$MUSL_PREFIX/include/linux"
cp "$ROOT_DIR/src/shims/sys/queue.h" "$MUSL_PREFIX/include/sys/queue.h"
cp "$ROOT_DIR/src/shims/linux/net.h" "$MUSL_PREFIX/include/linux/net.h"

cat > "$BUILD_DIR/musl-cc" <<EOF
#!/bin/sh
set -eu
MUSL_PREFIX=$MUSL_PREFIX
TERMUX_PREFIX=$TERMUX_PREFIX
KERNEL_UAPI=$KERNEL_UAPI
TARGET=$TARGET
CC=$HOST_CC
CLANG_RT_BUILTINS=$CLANG_RT_BUILTINS

COMMON_FLAGS="
  --target=\$TARGET
  -nostdinc
  -isystem \$MUSL_PREFIX/include
  -isystem \$TERMUX_PREFIX/include
  -isystem \$KERNEL_UAPI
"

LINKER_FLAGS="
  -nostdlib
  -static-pie
  -Wl,-z,now
  -Wl,-z,relro
"

CRT_BEGIN="
  \$MUSL_PREFIX/lib/rcrt1.o
  \$MUSL_PREFIX/lib/crti.o
"

CRT_END="
  -L\$MUSL_PREFIX/lib
  -lc
  \$CLANG_RT_BUILTINS
  \$MUSL_PREFIX/lib/crtn.o
"

should_link=1
explicit_nostdlib=0
for arg in "\$@"; do
  case "\$arg" in
    -c|-E|-S|-M|-MM|-MD|-MMD|-MG|-MP|-shared|-r)
      should_link=0
      ;;
    -nostdlib|-nostartfiles|-nodefaultlibs)
      explicit_nostdlib=1
      ;;
  esac
done

if [ "\$should_link" -eq 0 ] || [ "\$explicit_nostdlib" -eq 1 ]; then
  exec \$CC \$COMMON_FLAGS "\$@"
fi

exec \$CC \$COMMON_FLAGS \$LINKER_FLAGS \$CRT_BEGIN "\$@" \$CRT_END
EOF
chmod 755 "$BUILD_DIR/musl-cc"

mkdir -p "$TALLOC_PREFIX/include" "$TALLOC_PREFIX/lib"
cp "$ROOT_DIR/src/talloc-config.h" "$TALLOC_PREFIX/include/config.h"
"$BUILD_DIR/musl-cc" \
  -O2 \
  -fPIC \
  -D__STDC_WANT_LIB_EXT1__=1 \
  -DHAVE_CONFIG_H \
  -I"$TALLOC_PREFIX/include" \
  -I"$TALLOC_SRC/lib/replace" \
  -I"$TALLOC_SRC" \
  -c "$TALLOC_SRC/talloc.c" \
  -o "$TALLOC_PREFIX/talloc.o"
"$AR_TOOL" rcs "$TALLOC_PREFIX/lib/libtalloc.a" "$TALLOC_PREFIX/talloc.o"
"$RANLIB_TOOL" "$TALLOC_PREFIX/lib/libtalloc.a"
cp "$TALLOC_SRC/talloc.h" "$TALLOC_PREFIX/include/talloc.h"

cd "$PROOT_SRC/src"
"$MAKE_TOOL" clean
"$MAKE_TOOL" -j"$JOBS" V=1 \
  CC="$BUILD_DIR/musl-cc" \
  LD="$BUILD_DIR/musl-cc" \
  CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I$TALLOC_PREFIX/include -I$KERNEL_UAPI -I. -I$PROOT_SRC/src" \
  LDFLAGS="-L$TALLOC_PREFIX/lib -ltalloc -Wl,-z,noexecstack" \
  STRIP="$STRIP_TOOL" \
  OBJCOPY="$OBJCOPY_TOOL" \
  OBJDUMP="$OBJDUMP_TOOL"

cp "$PROOT_SRC/src/proot" "$PROOT_SO"
chmod 755 "$PROOT_SO"
cp "$PROOT_SRC/src/loader/loader" "$PROOT_LOADER_SO"
chmod 755 "$PROOT_LOADER_SO"

mkdir -p "$ROOTFS_STAGE"
tar -xzf "$DIST_DIR/alpine/alpine-minirootfs-3.23.4-aarch64.tar.gz" -C "$ROOTFS_STAGE"

for pkg in \
  dbus-libs-1.16.2-r1.apk \
  gdbm-1.26-r0.apk \
  libbz2-1.0.8-r6.apk \
  libcrypto3-3.5.6-r0.apk \
  libexpat-2.7.5-r0.apk \
  libffi-3.5.2-r0.apk \
  libgcc-15.2.0-r2.apk \
  libncursesw-6.5_p20251123-r0.apk \
  libnl3-3.11.0-r0.apk \
  libpanelw-6.5_p20251123-r0.apk \
  libssl3-3.5.6-r0.apk \
  libstdc++-15.2.0-r2.apk \
  mpdecimal-4.0.1-r0.apk \
  musl-1.2.5-r23.apk \
  ncurses-terminfo-base-6.5_p20251123-r0.apk \
  pcsc-lite-libs-2.4.0-r1.apk \
  python3-3.12.13-r0.apk \
  py3-wcwidth-0.2.13-r1.apk \
  readline-8.3.1-r0.apk \
  sqlite-libs-3.51.2-r0.apk \
  iw-6.17-r0.apk \
  wpa_supplicant-2.11-r3.apk \
  xz-libs-5.8.3-r0.apk \
  zlib-1.3.2-r0.apk; do
  tar -xzf "$DIST_DIR/alpine/$pkg" -C "$ROOTFS_STAGE"
done

rm -f \
  "$ROOTFS_STAGE"/.PKGINFO \
  "$ROOTFS_STAGE"/.INSTALL \
  "$ROOTFS_STAGE"/.pre-install \
  "$ROOTFS_STAGE"/.post-install \
  "$ROOTFS_STAGE"/.pre-upgrade \
  "$ROOTFS_STAGE"/.post-upgrade \
  "$ROOTFS_STAGE"/.trigger \
  "$ROOTFS_STAGE"/.SIGN.*

mkdir -p "$ROOTFS_STAGE/proc" "$ROOTFS_STAGE/sys" "$ROOTFS_STAGE/dev" "$ROOTFS_STAGE/dev/pts"
mkdir -p \
  "$ROOTFS_STAGE/etc" \
  "$ROOTFS_STAGE/run/wpa_supplicant" \
  "$ROOTFS_STAGE/var/run/wpa_supplicant" \
  "$ROOTFS_STAGE/wlantool" \
  "$ROOTFS_STAGE/usr/local/bin"
# Copy the entire tool directory so future scripts are packaged automatically.
cp -R "$ROOT_DIR/src/wlantool/." "$ROOTFS_STAGE/wlantool/"
rm -rf "$ROOTFS_STAGE/wlantool/__pycache__"
: > "$ROOTFS_STAGE/wlantool/vulnwsc.txt"
chmod 755 "$ROOTFS_STAGE/wlantool/scan.py"
ln -sf /usr/bin/python3 "$ROOTFS_STAGE/usr/local/bin/python"
cat > "$ROOTFS_STAGE/etc/wpa_supplicant.conf" <<'EOF'
ctrl_interface=/var/run/wpa_supplicant
ctrl_interface_group=0
update_config=1
EOF
chmod 600 "$ROOTFS_STAGE/etc/wpa_supplicant.conf"

tar \
  --format=gnu \
  --sort=name \
  --mtime='1970-01-01 00:00Z' \
  --numeric-owner \
  --owner=0 \
  --group=0 \
  -czf "$ROOTFS_ARCHIVE" \
  -C "$ROOTFS_STAGE" \
  .

cd "$BUILD_DIR"
"$HOST_CC" \
  --target="$ANDROID_TARGET" \
  -shared \
  -fPIC \
  -O2 \
  -Wall \
  -Wextra \
  -D_FILE_OFFSET_BITS=64 \
  -D_GNU_SOURCE \
  -D_LARGEFILE64_SOURCE=1 \
  -DNO_GZCOMPRESS \
  -I"$JNI_INCLUDE_DIR" \
  -I"$JNI_PLATFORM_INCLUDE_DIR" \
  -I"$ZLIB_SRC" \
  "$ROOT_DIR/src/librftool.c" \
  "$ZLIB_SRC/adler32.c" \
  "$ZLIB_SRC/crc32.c" \
  "$ZLIB_SRC/gzclose.c" \
  "$ZLIB_SRC/gzlib.c" \
  "$ZLIB_SRC/gzread.c" \
  "$ZLIB_SRC/inffast.c" \
  "$ZLIB_SRC/inflate.c" \
  "$ZLIB_SRC/inftrees.c" \
  "$ZLIB_SRC/trees.c" \
  "$ZLIB_SRC/zutil.c" \
  -o "$RFTOOL_SO"

chmod 755 "$RFTOOL_SO"
mkdir -p "$APP_ASSETS_DIR" "$APP_JNI_DIR"
cp "$ROOTFS_ARCHIVE" "$APP_ASSETS_DIR/rootfs.tar.gz"
cp "$RFTOOL_SO" "$APP_JNI_DIR/librftool.so"
cp "$PROOT_SO" "$APP_JNI_DIR/libproot.so"
cp "$PROOT_LOADER_SO" "$APP_JNI_DIR/libproot-loader.so"
chmod 755 "$APP_JNI_DIR/libproot-loader.so"
chmod 755 "$APP_JNI_DIR/libproot.so"
if command -v file >/dev/null 2>&1; then
  file "$RFTOOL_SO"
  file "$PROOT_SO"
  file "$PROOT_LOADER_SO"
fi
echo "build finished"
echo "librftool      : $RFTOOL_SO"
echo "libproot       : $PROOT_SO"
echo "libproot-loader: $PROOT_LOADER_SO"
echo "rootfs         : $ROOTFS_ARCHIVE"
echo "synced assets  : $APP_ASSETS_DIR/rootfs.tar.gz"
echo "synced jni     : $APP_JNI_DIR/librftool.so"
echo "synced jni     : $APP_JNI_DIR/libproot.so"
echo "synced jni     : $APP_JNI_DIR/libproot-loader.so"

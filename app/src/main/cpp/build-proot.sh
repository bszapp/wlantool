#!/bin/sh
set -eu

SELF_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROOT_REV=58aad2cb1c36ea6af7b32d76ccd5bf8d0a967939

die() {
  echo "build-proot.sh: $*" >&2
  exit 1
}

need_env() {
  eval "value=\${$1:-}"
  [ -n "$value" ] || die "missing env: $1"
}

need_tool() {
  command -v "$1" >/dev/null 2>&1 || die "missing tool: $1"
}

for name in ABI DIST_DIR WORK_DIR OUTPUT_DIR CC AR_TOOL RANLIB_TOOL STRIP_TOOL OBJCOPY_TOOL OBJDUMP_TOOL SYSROOT MAKE_TOOL; do
  need_env "$name"
done

need_tool tar
need_tool gzip
need_tool find
need_tool cp
need_tool rm
need_tool mkdir
need_tool chmod
need_tool sed
need_tool awk

JOBS=${JOBS:-$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 1)}

case "$ABI" in
  arm64-v8a)
    TARGET=aarch64-linux-musl
    KERNEL_TARGET=aarch64-linux-android
    ;;
  *)
    die "unsupported ABI: $ABI"
    ;;
esac

MUSL_ARCHIVE=$DIST_DIR/musl-1.2.6.tar.gz
TALLOC_ARCHIVE=$DIST_DIR/talloc-2.4.3.tar.gz
PROOT_ARCHIVE=$DIST_DIR/proot-android-compat-$PROOT_REV.tar.gz

[ -f "$MUSL_ARCHIVE" ] || die "missing distfile: $MUSL_ARCHIVE"
[ -f "$TALLOC_ARCHIVE" ] || die "missing distfile: $TALLOC_ARCHIVE"
[ -f "$PROOT_ARCHIVE" ] || die "missing distfile: $PROOT_ARCHIVE"

SRC_DIR=$WORK_DIR/src
BUILD_DIR=$WORK_DIR/build
MUSL_PREFIX=$WORK_DIR/musl
TALLOC_PREFIX=$WORK_DIR/talloc
SYSROOT_INCLUDE=$SYSROOT/usr/include
KERNEL_UAPI=$SYSROOT_INCLUDE/$KERNEL_TARGET
HOST_PREFIX=${HOST_PREFIX:-}

[ -f "$SYSROOT_INCLUDE/linux/limits.h" ] || die "missing linux UAPI headers under $SYSROOT_INCLUDE"
[ -f "$KERNEL_UAPI/asm/unistd.h" ] || die "missing target UAPI headers under $KERNEL_UAPI"

CLANG_RT_BUILTINS=$("$CC" --target="$TARGET" --rtlib=compiler-rt --print-libgcc-file-name 2>/dev/null || true)
if [ ! -f "$CLANG_RT_BUILTINS" ]; then
  CC_DIR=$(CDPATH= cd -- "$(dirname -- "$CC")/.." && pwd)
  CLANG_RT_BUILTINS=$(find "$CC_DIR/lib/clang" -name 'libclang_rt.builtins-aarch64-android.a' | sort | head -n 1)
fi
[ -n "${CLANG_RT_BUILTINS:-}" ] && [ -f "$CLANG_RT_BUILTINS" ] || die "could not locate compiler-rt builtins for $TARGET"

rm -rf "$WORK_DIR"
mkdir -p "$SRC_DIR" "$BUILD_DIR" "$OUTPUT_DIR"

tar -xzf "$MUSL_ARCHIVE" -C "$SRC_DIR"
tar -xzf "$TALLOC_ARCHIVE" -C "$SRC_DIR"
tar -xzf "$PROOT_ARCHIVE" -C "$SRC_DIR"

PROOT_SRC=$(find "$SRC_DIR" -mindepth 1 -maxdepth 1 -type d -name 'proot-*' | sort | head -n 1)
[ -n "$PROOT_SRC" ] || die "unable to locate extracted proot sources"
TALLOC_SRC=$SRC_DIR/talloc-2.4.3

cp "$SELF_DIR/patches/proot/src/syscall/rlimit.c" \
  "$PROOT_SRC/src/syscall/rlimit.c"
cp "$SELF_DIR/patches/proot/src/tracee/seccomp.c" \
  "$PROOT_SRC/src/tracee/seccomp.c"
cp "$SELF_DIR/patches/proot/src/extension/ashmem_memfd/ashmem_memfd.c" \
  "$PROOT_SRC/src/extension/ashmem_memfd/ashmem_memfd.c"
cp "$SELF_DIR/patches/proot/src/extension/sysvipc/sysvipc_msg.c" \
  "$PROOT_SRC/src/extension/sysvipc/sysvipc_msg.c"
cp "$SELF_DIR/patches/proot/src/extension/sysvipc/sysvipc_shm.c" \
  "$PROOT_SRC/src/extension/sysvipc/sysvipc_shm.c"

mkdir -p "$BUILD_DIR/musl-build"
cd "$BUILD_DIR/musl-build"
"$SRC_DIR/musl-1.2.6/configure" \
  --prefix="$MUSL_PREFIX" \
  --target="$TARGET" \
  CC="$CC --target=$TARGET --rtlib=compiler-rt" \
  AR="$AR_TOOL" \
  RANLIB="$RANLIB_TOOL" \
  LIBCC="$CLANG_RT_BUILTINS"
"$MAKE_TOOL" -j"$JOBS"
"$MAKE_TOOL" install

mkdir -p "$MUSL_PREFIX/include/sys" "$MUSL_PREFIX/include/linux"
cp "$SELF_DIR/shims/sys/queue.h" "$MUSL_PREFIX/include/sys/queue.h"
cp "$SELF_DIR/shims/linux/net.h" "$MUSL_PREFIX/include/linux/net.h"

cat > "$BUILD_DIR/musl-cc" <<EOF
#!/bin/sh
set -eu
MUSL_PREFIX=$MUSL_PREFIX
SYSROOT_INCLUDE=$SYSROOT_INCLUDE
KERNEL_UAPI=$KERNEL_UAPI
TARGET=$TARGET
CC=$CC
CLANG_RT_BUILTINS=$CLANG_RT_BUILTINS
HOST_PREFIX=$HOST_PREFIX

COMMON_FLAGS="
  --target=\$TARGET
  -nostdinc
  -isystem \$MUSL_PREFIX/include
"
if [ -n "\$HOST_PREFIX" ] && [ -d "\$HOST_PREFIX/include" ]; then
  COMMON_FLAGS="\$COMMON_FLAGS -isystem \$HOST_PREFIX/include"
fi
COMMON_FLAGS="\$COMMON_FLAGS -isystem \$SYSROOT_INCLUDE -isystem \$KERNEL_UAPI"

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
cp "$SELF_DIR/talloc-config.h" "$TALLOC_PREFIX/include/config.h"
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
  CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I$TALLOC_PREFIX/include -I$SYSROOT_INCLUDE -I$KERNEL_UAPI -I. -I$PROOT_SRC/src" \
  LDFLAGS="-L$TALLOC_PREFIX/lib -ltalloc -Wl,-z,noexecstack" \
  STRIP="$STRIP_TOOL" \
  OBJCOPY="$OBJCOPY_TOOL" \
  OBJDUMP="$OBJDUMP_TOOL"

cp "$PROOT_SRC/src/proot" "$OUTPUT_DIR/libproot.so"
cp "$PROOT_SRC/src/loader/loader" "$OUTPUT_DIR/libproot-loader.so"
chmod 755 "$OUTPUT_DIR/libproot.so" "$OUTPUT_DIR/libproot-loader.so"

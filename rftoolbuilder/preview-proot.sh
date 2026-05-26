#!/system/bin/sh
set -eu

case "$0" in
  */*) SELF_DIR=${0%/*} ;;
  *) SELF_DIR=. ;;
esac

cd "$SELF_DIR"
trap './build/out/rftool rmrf ./tmp >/dev/null 2>&1 || true' EXIT HUP INT TERM

./build/out/rftool rmrf ./tmp
./build/out/rftool extract ./build/out/rootfs-alpine-python-aarch64.tar.gz ./tmp/rootfs
./build/out/rftool proot ./tmp/rootfs

# rootfsbuilder

这个目录保留原方案，不改架构：

- Alpine Linux `minirootfs`
- 额外解包 Alpine `python3` 及其运行时 `.apk`
- 额外解包 Alpine `iw`、`libnl3`、`py3-wcwidth`、`wpa_supplicant` 及其运行时依赖
- Termux 分支 `proot` 源码静态构建
- 本地 `rftool` 负责解压、`chroot`、`proot` 和清理
- 本地 `src/wlantool/` 被固化进 rootfs 的 `/wlantool`

## 架构摘要

- `build.sh` 只在 `./build` 下工作
- `distfiles/` 只存预下载依赖
- `downloads.sh` 只负责联网下载并校验 SHA-256
- 运行时两个容器都用同一个 rootfs 包：
  - `rftool chroot ...`
  - `rftool proot ...`
- `rftool` 会在进入 `chroot`/`proot` 前强制设置 Linux 风格 `PATH`，并默认落到 `/wlantool` 的交互 shell

## 显式依赖统计

`build.sh` 当前显式使用 30 个预下载文件：

- 上游源码包 4 个
- Alpine `APKINDEX` 1 个
- Alpine `minirootfs` 1 个
- 额外 Alpine `.apk` 24 个

### 上游源码包

| 文件 | 项目 | 下载地址 | 上游许可 | 作用 |
| --- | --- | --- | --- | --- |
| `musl-1.2.6.tar.gz` | musl | `https://musl.libc.org/releases/musl-1.2.6.tar.gz` | MIT | 构建静态 musl 工具链和 CRT |
| `talloc-2.4.3.tar.gz` | talloc | `https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz` | LGPL-3.0-or-later | 给 `proot` 提供 `libtalloc.a` |
| `zlib-1.3.1.tar.gz` | zlib | `https://zlib.net/fossils/zlib-1.3.1.tar.gz` | Zlib | 编进 `rftool` 的解压实现 |
| `proot-termux-58aad2cb1c36ea6af7b32d76ccd5bf8d0a967939.tar.gz` | termux/proot | `https://codeload.github.com/termux/proot/tar.gz/58aad2cb1c36ea6af7b32d76ccd5bf8d0a967939` | GPL-2.0-or-later | 构建静态 `proot` |

### Alpine 预下载文件

| 文件 | 来源项目 | 下载地址 | 包许可或说明 |
| --- | --- | --- | --- |
| `alpine/APKINDEX.tar.gz` | Alpine main repo index | `https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/APKINDEX.tar.gz` | 仓库索引，不是单独源码项目 |
| `alpine/alpine-minirootfs-3.23.4-aarch64.tar.gz` | Alpine Linux minirootfs | `https://dl-cdn.alpinelinux.org/alpine/v3.23/releases/aarch64/alpine-minirootfs-3.23.4-aarch64.tar.gz` | 预构建 rootfs 聚合包，内部是多包混合许可 |
| `alpine/dbus-libs-1.16.2-r1.apk` | dbus | `https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/dbus-libs-1.16.2-r1.apk` | AFL-2.1 OR GPL-2.0-or-later |
| `alpine/gdbm-1.26-r0.apk` | gdbm | `https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/gdbm-1.26-r0.apk` | GPL-3.0-or-later |
| `alpine/libbz2-1.0.8-r6.apk` | bzip2 | `https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/libbz2-1.0.8-r6.apk` | `bzip2-1.0.6` |
| `alpine/libcrypto3-3.5.6-r0.apk` | openssl | `https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/libcrypto3-3.5.6-r0.apk` | Apache-2.0 |
| `alpine/libexpat-2.7.5-r0.apk` | expat | `https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/libexpat-2.7.5-r0.apk` | MIT |
| `alpine/libffi-3.5.2-r0.apk` | libffi | `https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/libffi-3.5.2-r0.apk` | MIT |
| `alpine/libgcc-15.2.0-r2.apk` | gcc runtime | `https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/libgcc-15.2.0-r2.apk` | GPL-2.0-or-later AND LGPL-2.1-or-later |
| `alpine/libncursesw-6.5_p20251123-r0.apk` | ncurses | `https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/libncursesw-6.5_p20251123-r0.apk` | X11 |
| `alpine/libnl3-3.11.0-r0.apk` | libnl3 | `https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/libnl3-3.11.0-r0.apk` | LGPL-2.1-or-later |
| `alpine/libpanelw-6.5_p20251123-r0.apk` | ncurses | `https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/libpanelw-6.5_p20251123-r0.apk` | X11 |
| `alpine/libssl3-3.5.6-r0.apk` | openssl | `https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/libssl3-3.5.6-r0.apk` | Apache-2.0 |
| `alpine/libstdc++-15.2.0-r2.apk` | gcc runtime | `https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/libstdc++-15.2.0-r2.apk` | GPL-2.0-or-later AND LGPL-2.1-or-later |
| `alpine/mpdecimal-4.0.1-r0.apk` | mpdecimal | `https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/mpdecimal-4.0.1-r0.apk` | BSD-2-Clause |
| `alpine/musl-1.2.5-r23.apk` | musl | `https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/musl-1.2.5-r23.apk` | MIT |
| `alpine/ncurses-terminfo-base-6.5_p20251123-r0.apk` | ncurses | `https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/ncurses-terminfo-base-6.5_p20251123-r0.apk` | X11 |
| `alpine/pcsc-lite-libs-2.4.0-r1.apk` | pcsc-lite | `https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/pcsc-lite-libs-2.4.0-r1.apk` | BSD-3-Clause AND BSD-2-Clause AND ISC |
| `alpine/python3-3.12.13-r0.apk` | CPython | `https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/python3-3.12.13-r0.apk` | PSF-2.0 |
| `alpine/py3-wcwidth-0.2.13-r1.apk` | wcwidth | `https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/py3-wcwidth-0.2.13-r1.apk` | MIT |
| `alpine/readline-8.3.1-r0.apk` | readline | `https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/readline-8.3.1-r0.apk` | GPL-3.0-or-later |
| `alpine/sqlite-libs-3.51.2-r0.apk` | SQLite | `https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/sqlite-libs-3.51.2-r0.apk` | `blessing` |
| `alpine/iw-6.17-r0.apk` | iw | `https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/iw-6.17-r0.apk` | ISC |
| `alpine/wpa_supplicant-2.11-r3.apk` | wpa_supplicant | `https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/wpa_supplicant-2.11-r3.apk` | BSD-3-Clause |
| `alpine/xz-libs-5.8.3-r0.apk` | xz | `https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/xz-libs-5.8.3-r0.apk` | GPL-2.0-or-later AND 0BSD AND Public-Domain AND LGPL-2.1-or-later |
| `alpine/zlib-1.3.2-r0.apk` | zlib | `https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/zlib-1.3.2-r0.apk` | Zlib |

### 额外 `.apk` 许可计数

以下计数只针对 `build.sh` 额外解包的 24 个 `.apk`：

| 许可字符串 | 数量 |
| --- | ---: |
| GPL-3.0-or-later | 2 |
| Apache-2.0 | 2 |
| MIT | 4 |
| GPL-2.0-or-later AND LGPL-2.1-or-later | 2 |
| X11 | 3 |
| BSD-2-Clause | 1 |
| PSF-2.0 | 1 |
| `bzip2-1.0.6` | 1 |
| `blessing` | 1 |
| ISC | 1 |
| LGPL-2.1-or-later | 1 |
| AFL-2.1 OR GPL-2.0-or-later | 1 |
| BSD-3-Clause AND BSD-2-Clause AND ISC | 1 |
| BSD-3-Clause | 1 |
| GPL-2.0-or-later AND 0BSD AND Public-Domain AND LGPL-2.1-or-later | 1 |
| Zlib | 1 |

## minirootfs 基包统计

当前 `alpine-minirootfs-3.23.4-aarch64.tar.gz` 里的 `lib/apk/db/installed` 记录了 16 个基包：

| 包名 | 版本 | 许可 | 来源项目 |
| --- | --- | --- | --- |
| alpine-baselayout | 3.7.2-r0 | GPL-2.0-only | alpine-baselayout |
| alpine-baselayout-data | 3.7.2-r0 | GPL-2.0-only | alpine-baselayout |
| alpine-keys | 2.6-r0 | MIT | alpine-keys |
| alpine-release | 3.23.4-r0 | MIT | alpine-base |
| apk-tools | 3.0.6-r0 | GPL-2.0-only | apk-tools |
| busybox | 1.37.0-r30 | GPL-2.0-only | busybox |
| busybox-binsh | 1.37.0-r30 | GPL-2.0-only | busybox |
| ca-certificates-bundle | 20260413-r0 | MPL-2.0 AND MIT | ca-certificates |
| libapk | 3.0.6-r0 | GPL-2.0-only | apk-tools |
| libcrypto3 | 3.5.6-r0 | Apache-2.0 | openssl |
| libssl3 | 3.5.6-r0 | Apache-2.0 | openssl |
| musl | 1.2.5-r23 | MIT | musl |
| musl-utils | 1.2.5-r23 | MIT AND BSD-2-Clause AND GPL-2.0-or-later | musl |
| scanelf | 1.3.8-r2 | GPL-2.0-only | pax-utils |
| ssl_client | 1.37.0-r30 | GPL-2.0-only | busybox |
| zlib | 1.3.2-r0 | Zlib | zlib |

### minirootfs 基包许可计数

| 许可字符串 | 数量 |
| --- | ---: |
| GPL-2.0-only | 8 |
| MIT | 3 |
| Apache-2.0 | 2 |
| MPL-2.0 AND MIT | 1 |
| MIT AND BSD-2-Clause AND GPL-2.0-or-later | 1 |
| Zlib | 1 |

## `src/` 拼装来源

| 路径 | 基于什么项目 | 上游许可 | 本地改动 |
| --- | --- | --- | --- |
| `src/rftool.c` | 本地自写 | 无上游项目 | 实现 `extract` / `rmrf` / `chroot` / `proot` 入口 |
| `src/talloc-config.h` | `talloc 2.4.3` 生成配置头 | 跟随 talloc | 固化一次生成结果，避免在正式构建里再跑 talloc 配置探测 |
| `src/shims/sys/queue.h` | 本地兼容层 | 无上游项目 | 包装宿主 `<sys/queue.h>`，并取消 `__unused` 宏冲突 |
| `src/shims/linux/net.h` | 本地兼容层 | 无上游项目 | 补 Linux socket 子调用编号给 `proot` 的 seccomp 代码 |
| `src/overrides/proot/src/syscall/rlimit.c` | termux/proot 同路径文件 | GPL-2.0-or-later | `struct rlimit64` 改为 `struct rlimit` |
| `src/overrides/proot/src/tracee/seccomp.c` | termux/proot 同路径文件 | GPL-2.0-or-later | `struct statfs64` 改为 `struct statfs`，`statfs64()` 改为 `statfs()` |
| `src/overrides/proot/src/extension/ashmem_memfd/ashmem_memfd.c` | termux/proot 同路径文件 | GPL-2.0-or-later | 增加 `#include <string.h>` |
| `src/overrides/proot/src/extension/sysvipc/sysvipc_msg.c` | termux/proot 同路径文件 | GPL-2.0-or-later | `<sys/errno.h>` 改为 `<errno.h>`，补 `MSG_COPY` 兜底定义 |
| `src/overrides/proot/src/extension/sysvipc/sysvipc_shm.c` | termux/proot 同路径文件 | GPL-2.0-or-later | `<sys/errno.h>` 改为 `<errno.h>`，补 `TEMP_FAILURE_RETRY` 兜底宏 |
| `src/wlantool/scan.py` | 本地 `/data/data/com.termux/files/home/scan.py` 快照裁剪版 | 本地脚本，无额外上游源码包 | 去掉历史记录逻辑，纳入 rootfs 的 `/wlantool/scan.py` |

## 其它拼装说明

- `build.sh` 直接编译 `zlib 1.3.1` 的这些上游源码文件进 `rftool`，没有本地改动：
  - `adler32.c`
  - `crc32.c`
  - `gzclose.c`
  - `gzlib.c`
  - `gzread.c`
  - `inffast.c`
  - `inflate.c`
  - `inftrees.c`
  - `trees.c`
  - `zutil.c`
- `build.sh` 通过 `llvm-objcopy` 把构建出的 `proot` 转成目标文件，再链接进 `rftool`
- `build.sh` 会把 `src/wlantool/` 整个目录递归复制到 rootfs 的 `/wlantool/`，后续新增脚本会自动被带进去，并额外创建空的 `vulnwsc.txt`
- `build.sh` 会创建 `/usr/local/bin/python -> /usr/bin/python3`，这样进入容器后可以直接敲 `python`
- `build.sh` 会离线解包 `wpa_supplicant`、`dbus-libs`、`pcsc-lite-libs`，这样 rootfs 内可以直接使用 `wpa_supplicant`
- `build.sh` 会预置 `/etc/wpa_supplicant.conf`，并准备好 `/var/run/wpa_supplicant` 控制目录

## 重下载和离线构建

联网阶段：

```sh
cd ~/rootfsbuilder
./downloads.sh
```

离线构建阶段：

```sh
cd ~/rootfsbuilder
chmod -R a-w src distfiles
./build.sh
```

预览：

```sh
/system/bin/sh ./preview-proot.sh
su -c 'cd /data/data/com.termux/files/home/rootfsbuilder && /system/bin/sh ./preview-chroot.sh'
```

两个 preview 共用工作目录下的 `./tmp`，应串行运行，不要并行执行。

## chroot 扫描说明

- `preview-chroot.sh` 只依赖 `/system/bin/sh`、`su`、构建出来的静态 `rftool` 和离线 rootfs，不依赖 Termux 运行时
- 真机硬件访问依赖 `rftool chroot` 的内建挂载：会把宿主 `/dev`、`/sys` 绑定进 rootfs，并挂载 `proc`
- `preview-chroot.sh` 和 `preview-proot.sh` 都会直接进入 `/wlantool` 的交互 shell
- 进入容器后，`PATH` 已包含 `/usr/local/bin:/usr/bin:/bin:/usr/local/sbin:/usr/sbin:/sbin`
- 进入容器后可以直接运行：

```sh
python
python3 scan.py -i wlan0 -scan
wpa_supplicant -B -i wlan0 -c /etc/wpa_supplicant.conf
wpa_cli -i wlan0
```

基于alpinelinux打包的一些wlan操作工具环境，android root直接运行

包含工具：
- python 3.12
- iw
- wpa_supplicant
- oneshot.py（详见 https://github.com/kimocoder/oneshot ）

常用命令：
```
python3 oneshot.py -i wlan0 -K
```

```
wpa_supplicant -B -i wlan0 -c /etc/wpa_supplicant.conf && wpa_cli -i wlan0
```

使用chroot才能运行上述命令，proot只能用python，其他权限不够

chroot必须设备有root（su命令可用）

其他：项目源码全透明构建不含预编译二进制（环境从alpinelinux等官方源下载预编译系统包），发行版使用github action构建

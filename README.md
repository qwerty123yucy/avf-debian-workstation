# AVF Linux · Debian 13 ARM64

在**未 root** 的 Android 15/16 设备上，通过 Android Virtualization Framework（AVF）运行标准、完整的 **Debian 13 (Trixie, ARM64)** 虚拟机。不依赖 Termux，完成一次性权限授予后可关闭调试开关，直接通过 App 内置控制台或任意本地 SSH 客户端日常使用。

---

## 为什么这样做（设计动机与取舍）

### 相较于 PRoot / 用户态容器方案
- **规避系统调用拦截开销**：PRoot 依赖 `ptrace` / `seccomp` 拦截并改写路径与系统调用，在密集小文件 I/O、多进程编译和 `apt` 解包场景下开销显著；AVF 直接跑在硬件虚拟化（KVM / GenieZone）上，无用户态 syscall 转译层。
- **规避伪造 root 与内核能力缺失**：PRoot 只能模拟 UID 0，缺少真实内核命名空间、cgroups、完整 `systemd`、原生 `ext4`、真实 `zram` 与网络协议栈，且受 Android 宿主机 `SELinux` 和内核裁剪约束；AVF 客户机运行标准未修改的 Debian `linux-image-cloud-arm64` 内核，软件生态和行为与常规 ARM64 Linux 服务器一致。

### 相较于常见 AVF 玩法的关键取舍
- **UEFI 自启动 + 宿主保留初始种子（而非宿主托管内核）**
  - 宿主不保存也不解析 `vmlinuz` / `initrd.img`，只交给系统自带 `/apex/com.android.virt/etc/u-boot.bin` 从单张 64 GiB 稀疏 GPT 盘引导 GRUB 与 Debian。客户机内部可自由执行 `apt full-upgrade`、`update-initramfs`、`update-grub` 与 `reboot`（宿主捕获 `STOP_REASON_REBOOT` 自动拉起）。
  - 宿主私存一份只读压缩的初始种子（`seed.simg.gz`，~`490 MiB`）。一旦客户机配置改坏或内核升级异常，可通过 App 的 **Reset VM**（10 秒红色倒计时防误触）在约 6 秒内还原干净系统。
- **用户态双栈网络走 App UID（而非 AVF 默认直通 `vmnic`）**
  - 关闭 AVF 原生 `useNetwork(false)`，避免虚拟机流量绕过宿主 `VpnService` 与 Android「无 VPN 时阻止连接」开关。
  - 客户机 `vmnet-guest` 通过 `virtio-vsock:2223` 封装 IPv4/IPv6 包（`10.0.2.2/24` + `fd00:a7f::2/64`），宿主 `libvmnet.so` 以 `com.avf.linux` 自身 UID 在用户态运行 gVisor 双栈协议栈对外建连，自动继承宿主当前网络与 VPN 路由，并按需同步 Android 活跃主备 DNS（`10.0.2.3` / `10.0.2.4`）。
- **纯精简外设 + 默认不持 WakeLock**
  - 不挂载虚拟 GPU、音频、USB 或 `virtio-fs`，只保留运行稳定工作站所需的最小设备面。
  - 默认 `WakeLock: OFF`，放行手机灭屏正常休眠；仅在需要息屏跑无人值守任务时，从概览页、通知栏或 CLI 显式开启。

---

## 关键调优与实测取舍

- **零额外拷贝的 vsock 网络路径**：`VmService` 通过 Unix Socket `SCM_RIGHTS` 将 `vsock:2223` FD 直接移交给原生 Go 进程 `libvmnet.so`，无 Java 转发线程；`vmnet-guest` 在 raw FD 上前置 `O_NONBLOCK` 接入 Go epoll 轮询并聚合突发刷写（单次最多 64 包）；`libvmnet.so` 仅在收到 DNS 查询时懒检查 `active-dns.txt` 修改时间，实测空闲 CPU `0.0%`、常驻内存 ~`4.6 MiB`。
- **稀疏存储与写放大控制**：64 GiB 稀疏盘实际初始占用仅 ~`1.25 GiB`；根分区 `ext4` 启用 `noatime,lazytime,x-systemd.growfs`，配合每周 `fstrim.timer` 回收空洞；`journald` 限制 `SystemMaxUse=128M`；首次启动由 `avf-boot-setup.service`（`After=local-fs.target`）提前完成分区扩容检查与缺失的 SSH 主机密钥自动生成。
- **内存、CPU 与计时器**：宿主 4 GiB 按需分配 + 客户机 ~`2 GiB LZ4 zram0`（`vm.page-cluster=0` 避免无意义预读）；`CPU_TOPOLOGY_MATCH_HOST` 透传大小核拓扑；保留内核默认 `kernel.watchdog` 与 `vm.stat_interval`（实测在 MediaTek GenieZone 上禁用或拉长这两项会导致宿主 `crosvm` 空闲 CPU 反常暴涨约 35 倍）。
- **数据完整性、优雅关机与防误操作**：支持从系统文件选择器直接导入下载目录中的 `.simg.gz` / `.simg`；导入前先校验文件头魔数，解包过程写入 `.tmp`、完整读至流末尾触发 GZIP CRC32 校验并 `fsync` 后再原子替换 `disk.img` 与 `seed.simg.gz`。客户机 `vmnet-guest` 监听仅限宿主（`CID=2`）访问的 `vsock:2224` 电源控制端口；宿主在点击 Stop/Restart、导入/重置前、移除最近任务卡片及收到系统关机广播时，先下发 `sync` + `systemctl poweroff` 等待客户机有序卸载文件系统（超时再回退强制停机），并保留上一轮会话的 `last-console.log` 与 `last-vmlog.log` 供排障读取。
- **生命周期状态机与虚拟化终端**：宿主控制面采用单锁可形式化状态机（`VmController`）隔离物理阶段、目标意图与单次会话资源（`VmSession`），配合同进程 `LocalBinder` 与不可变状态快照避免跨轮次竞态；内置控制台采用流式 VT100/ANSI 行环形缓冲（保留最近 2000 行 / 160 KB，折叠引导阶段冗余空行）与按视口虚拟化绘制的 `Canvas` 终端（流式刷新无重排开销，避免高速引导刷屏导致的空白或卡顿），软键盘采用 `BaseInputConnection` 增量差值发送。
- **实测基线**（MediaTek Dimensity 9400 / GenieZone / Android 16）：Debian 13 内核 + 用户态启动约 `4.7s`，`journalctl -b -p warning..emerg` 无告警，空闲时宿主 `crosvm` 占整机 CPU ~`0.25%`。

---

## 前提条件

- **系统与硬件**：Android 15 / 16 设备，底层已开放非保护 AVF 虚拟机支持（可通过系统是否具备 `/apex/com.android.virt/etc/u-boot.bin` 及 `pm list features | grep virtualization` 判断）。
- **一次性授权说明**：Android 将 `MANAGE_VIRTUAL_MACHINE` 与 `USE_CUSTOM_VIRTUAL_MACHINE` 设为 `signature|development` 级别，且自定义镜像接口属于隐藏 API，普通 APK 无法弹窗自申请。因此**必须通过电脑 ADB 或本机无线调试执行一次下方 4 条命令**；授权结果持久生效，之后即可关闭调试开关，日常完全不需要 ADB 或 Termux。

---

## 快速上手（无需 Termux）

1. **下载与安装**
   - 从 GitHub Releases 下载并安装 `avf-linux.apk`，同时将公开版初始种子 `seed-debian13-arm64.simg.gz` 下载到手机（默认在 `Download/` 目录即可）。
2. **一次性权限授予与后台放行**
   - 通过 ADB（或无线调试 Shell）执行一次：
     ```bash
     adb shell pm grant --user 0 com.avf.linux android.permission.MANAGE_VIRTUAL_MACHINE
     adb shell pm grant --user 0 com.avf.linux android.permission.USE_CUSTOM_VIRTUAL_MACHINE
     adb shell pm grant --user 0 com.avf.linux android.permission.POST_NOTIFICATIONS
     adb shell settings put global hidden_api_blacklist_exemptions 'Landroid/system/virtualmachine/'
     ```
   - 打开 **AVF Linux -> Settings**，在系统电池设置中允许应用后台活动，并在系统最近任务界面给应用卡片加锁（避免 ColorOS 等系统在未锁卡上滑清理时按包名发送 `SIGKILL` 强杀进程）。
3. **导入、启动与使用**
   - 在 App 内点击 **Import**，直接选取刚下载的 `seed-debian13-arm64.simg.gz`（等待约 6 秒完成校验与解包）。
   - 点击 **Start VM**，切到 **Console** 标签页等待约 15–20 秒即自动以 `root` 登录 Debian 13（首次启动自动生成本机独立的 `/etc/machine-id` 与 SSH 主机密钥）。
   - > 说明：内置 **Console** 是行缓冲串口终端（解析常见行内光标与清行控制码，不支持 `vim`、`tmux`、`htop` 等全屏交替屏幕应用），定位为首次引导与维护抢救；日常开发建议按下方配置本地 SSH 客户端接入。

---

## 可选：配置本地 SSH 与 Termux CLI

虚拟机通过 `virtio-vsock:2222` 将 SSH 暴露在宿主 `127.0.0.1:2222`（仅允许公钥认证，不受 VPN 路由或阻断开关影响）。

- **通用 Android SSH 客户端**：生成密钥对后，在 App 的 **Console** 中将公钥写入 `/root/.ssh/authorized_keys`（`chmod 600`），客户端连接 `root@127.0.0.1:2222` 即可。
- **Termux CLI 管理**：如需命令行启停/状态查询/切换 WakeLock，可安装 [`scripts/avf-vm`](scripts/avf-vm)（或运行 [`scripts/setup-termux.sh`](scripts/setup-termux.sh)），在 App 中点击 **Copy Token** 保存到 `~/.config/avf-linux/token`（`chmod 600`）：
  ```bash
  avf-vm status         # 查看运行态、稀疏盘占用、网络模式与 WakeLock
  avf-vm start          # 启动虚拟机
  avf-vm wakelock on    # 息屏长任务按需持锁（off 恢复默认）
  avf-vm reset          # 从宿主初始种子还原系统
  avf-vm stop           # 优雅关机并等待停稳
  avf-vm console --last # 查看上一轮会话保存的串口或 vmlog (--vmlog)
  ```
- **可选：按需开启宿主机 ADB 调试隧道**：虚拟机默认不暴露宿主机回环接口。如需在 Debian 内通过独立 `adb` 调试本机（前提：宿主已开启 `adb tcpip 5555`，客户机已 `apt install -y adb`），可使用 [`scripts/avf-adb`](scripts/avf-adb)（通过 OpenSSH `ControlMaster` 独立隧道将宿主 `127.0.0.1:5555` 映射至客户机 `127.0.0.1:15555`——与交互 `ssh avf` 解耦以避免退出挂起，并避开 ADB `5555..5585` 本地模拟器扫描段以防重复设备）：
  ```bash
  avf-adb on            # 启动独立隧道并在客户机连接 127.0.0.1:15555（首次需在手机弹窗确认授权）
  avf-adb status        # 查看隧道状态与客户机 adb devices
  avf-adb off           # 断开连接并关闭隧道
  ```

---

## 已知边界

- **时钟与 NTP**：Debian 主线 ARM64 云内核不驱动 AVF 的 PL030 虚拟 RTC（`RTC time: n/a`）。冷启动或重置后墙钟由 `systemd-timesyncd` 通过 UDP `123` 同步；若所连 VPN 屏蔽 UDP `123`，需放行或手动校时。
- **Android 私人 DNS（DoT）与 `/etc/resolv.conf` 托管**：`vmnet-guest.service` 每次启动都会原子覆写客户机 `/etc/resolv.conf` 为固定虚拟 DNS（`10.0.2.3` / `10.0.2.4`），手动修改该文件会在重启服务或虚拟机后重置。客户机 DNS 查询由宿主 `libvmnet.so` 以 `com.avf.linux` 身份发往当前活跃网络 `LinkProperties` DNS 服务器的 53 端口；开启 VPN 时完整走 VPN 隧道，未开 VPN 时不经过系统级私人 DNS（DoT）。
- **回环隔离与本地 OAuth 回调**：客户机 `127.0.0.1` 与 Android 宿主机隔离。若客户机内运行的 CLI 认证需要由手机浏览器回调 `http://localhost:<port>`（如部分 Google OAuth / `rclone` 流程），可在宿主机侧通过 `ssh -L <port>:127.0.0.1:<port> avf` 临时转发该端口完成授权。
- **GenieZone 内存回收**：在 MediaTek GenieZone 平台下客户机内存页按需分配（上限 4 GiB），不会在运行时通过 balloon 自动回缩，停机或重启后释放。
- **系统 OTA 更新**：部分厂商大版本 OTA 可能重置全局 `hidden_api_blacklist_exemptions`；若更新系统后无法启动 VM，重新执行该条 `settings put` 命令即可。
- **签名密钥与备份**：若自行编译安装，请妥善备份 `signing/avf-linux.jks`；丢失密钥将无法覆盖升级，卸载 App 会连带清除私有目录中的 `disk.img`。

---

## 目录结构与自行构建

```text
app/src/main/       Android Material 3 宿主应用源码（com.avf.linux v1.7.0）
app/compile-stubs/  编译期 AVF API 最小桩声明（不打包进 DEX）
vmnet/              Go 源码：宿主 gVisor 双栈网关（libvmnet.so）与客户机隧道（vmnet-guest）
guest/              客户机 systemd 单元、GRUB/fstab/sysctl/sshd 配置与 provision.sh
scripts/            build_apk.py、make_golden_seed.py、avf-vm、avf-adb、setup-termux.sh
```

构建前需准备 Go 1.24+、JDK（`javac`/`keytool`）及 Android SDK Build-Tools（设置 `ANDROID_HOME`）：

```bash
# 1. 编译宿主与客户机双端网络二进制
cd vmnet
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags="-s -w" -o ../build/libvmnet.so ./cmd/vmnet-host
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags="-s -w" -o ../build/vmnet-guest ./cmd/vmnet-guest
cd ..

# 2. 编译、16 KB 对齐并签名 APK（可通过 AVF_KS_PASS 指定密钥库口令）
python3 scripts/build_apk.py

# 3. 同步客户机配置并导出脱敏版初始种子（默认清除 SSH 密钥与 machine-id）
./guest/provision.sh
python3 scripts/make_golden_seed.py build/seed-debian13-arm64.simg.gz
```

## License

[MIT](LICENSE)

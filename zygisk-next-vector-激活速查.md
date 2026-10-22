# Zygisk Next + Vector 激活速查（GhostLock / late-load 环境）

> 设备：Xiaomi 25080RABDC（codename `lapis`）· Android 16 (SDK 36) · HyperOS OS3.0.308.0.WPPCNXM · kernel 6.1.138 · arm64-v8a
> Root：KernelSU LKM 形态，ksud 3.3.0，`root_ver` 32601
> 环境：GhostLock（CVE-2026-43499）临时提权 —— **只在内存，普通重启即全部失效**

---

## 一句话原理

KernelSU 的「已激活」只代表内核模块挂载 + `su` 可用，**它不含 Zygisk**。
Vector（JingMatrix 维护的 LSPosed 续作）是一个 **Zygisk 模块**，必须由 Zygisk Next 这个「Zygisk 提供者」把它加载进 zygote。

本机 KernelSU 是 late-load 模式，**`post-fs-data` 阶段的模块脚本默认被跳过** → Zygisk Next 的 daemon 起不来 → Zygisk 模块注册表为空 → Vector 永远「未激活」。

**关键机理（此前所有尝试失败的原因）：**
`ksud post-fs-data` **只有在 `/data/adb/modules_update/` 里有待应用内容时，才会执行模块的 `post-fs-data.sh`**。
暂存区为空时，ksud 判定「无更新」，直接跳过整个 apply 流程（只应用 sepolicy）。
所以必须**先重新暂存模块**，再触发 `post-fs-data`。

---

## 每次重启后必做的步骤

```sh
# 1) 先重新暂存模块 —— 这一步是全部的前提
adb shell "su -c 'ksud module install \"/sdcard/Download/Zygisk Next-v1.5.2.zip\"'"

# 2) 触发 post-fs-data —— 暂存区非空，模块脚本才会真正执行
adb shell "su -c 'ksud post-fs-data >/dev/null 2>&1'"

# 3) 让 Zygisk 接管两个 zygote
adb shell "setprop ctl.restart zygote"
adb shell "setprop ctl.restart zygote_secondary"
```

> 第 3 步会连带重启 `system_server`，属正常现象，不是崩溃。

---

## 验收标准

| 检查项 | 命令 | 期望结果 |
|---|---|---|
| ZN 状态 | `su -c 'znctl status'` | `zygote_states:2`、`inject_state:1`、`modules_with_issue:0` |
| 已加载模块 | 同上 | `modules64:2,zygisk_tcqt,zygisk_vector` |
| Zygisk 侧加载表 | `su -c 'cat /data/adb/zygisksu/modules_info'` | 每个模块一行（`zygisk_vector 64 fd=9 ...`）；`maps` 里查不到是正常的，见下文 |
| 管理器显示 | `grep -m1 description /data/adb/modules/zygisksu/module.prop` | `[✅zygote, ✅zygote_secondary, Root: ✅KernelSU (32601), ZL]` |
| **Vector daemon** | `ps -A \| grep vectord` | 有 `vectord` 进程；没有就 `ksud services` |
| Vector 状态 | `su -c '/data/adb/lspd/cli status --json'` | `Framework Version` 有值、`Enabled Modules: N`；报 `Connection refused` = daemon 没起 |
| 真实注入 | `grep -a "Loaded module" /data/adb/lspd/log/verbose_*.log \| tail` | 出现 `Loaded module xxx successfully` |

---

## 两个会骗人的输出（别据此判断失败）

- `znctl dump-zn -sa` 里的 `Modules: 0` / `Injected Processes: 0`
  —— 它列的是 module↔service 绑定关系，**不是已加载的 .so**。已实测：这里显示 0 时 `zygisk_vector` 实际注入是成功的。
- `/data/adb/lspd/cli status --json` 的 `isFdAttached: false`
  —— 指 CLI 自己有没有挂上 daemon 的**日志流 fd**，与模块注入无关。

**判断「是否真的激活」只看两处：日志里的 hook 记录 + `Enabled Modules` 计数。**

---

## Vector daemon 掉线了怎么救（2026-10-22 实测）

**症状：** 模块全都不生效，`su -c '/data/adb/lspd/cli status'` 报

```
Error: Socket Failure: Connection refused
```

`ps -A | grep vectord` 查不到进程（注意进程名叫 **`vectord`**，不是 lspd / daemon）。

### 一行修复

```sh
adb shell su -c '/data/adb/ksud services'
```

约 5 秒后 `vectord` 出现，`cli status` 恢复。实测输出：

```
Framework Version: 2.2
Version Code: 3112
API Version: 102
Enabled Modules: 69
```

### 为什么别的办法都起不来（都试过）

| 方式 | 结果 |
|---|---|
| `su -c 'sh /data/adb/modules/zygisk_vector/service.sh'` | 无效。脚本里 `unshare --propagation slave -m` 报 `Unknown option 'propagation'` —— 设备的 `/system/bin/unshare` 是 **toybox 0.8.12，只支持 `-i -m -n -p -u -U -r`** |
| `unshare -m ./daemon` | 静默退出，无任何输出（daemon 内部 `exec ... >/dev/null 2>&1` 把错误吞了） |
| `/data/adb/ksu/bin/busybox unshare ...` | 同样静默退出 |
| 直接前台跑 `app_process ... VectorDaemon` | 15 秒无输出 —— 缺 zygisk 侧 binder 环境，单独起不来 |
| **`ksud services`** | ✅ 唯一有效。KernelSU 的 service 阶段 PATH 里有 `/data/adb/ksu/bin/busybox`，它提供的 `unshare` **支持 `--propagation`** |

**结论：** `service.sh` 本身是对的，错的是在普通 su 环境下 `unshare` 解析到了 toybox。只有走 KernelSU 的 service 触发才能拿到 busybox 版本。

### 什么操作会导致 daemon 掉线

- **`ksud soft-reboot`** —— 实测会杀掉 `vectord` 且**不会自动拉起**，之后必须补一条 `ksud services`
- 直接 `kill` 掉 vectord 自然也会，同理

---

## 模块「enabled」但完全不生效怎么查

`cli modules ls` 显示 `enabled` **不等于**真的在 hook。按下面顺序查：

### 1. 日志里有没有真加载

```sh
adb shell "su -c 'ls -t /data/adb/lspd/log/verbose_* | head -1'"
adb shell "su -c 'grep -a \"Loading module\\|Loaded module\" /data/adb/lspd/log/verbose_xxx.log | tail'"
```

有 `Loaded module xxx successfully` 才算加载成功。只有 `Loading` 没有 `Loaded` = 加载中途抛异常（日志里紧跟 `E/VectorLegacyBridge`）。

### 2. 模块的 scope 是不是空的（最容易踩）

Vector 只按**数据库里的 scope 记录**决定往哪些进程注入。有些模块在 APK 里声明了固定作用域：

```
META-INF/xposed/module.prop   →  staticScope=true
META-INF/xposed/scope.list    →  每行一个包名
```

对这类模块：

- `vector-cli scope add <模块> <包名>/0` 会直接报错
  `Error: ... fixes its scope in module.prop, so xxx cannot be added. It claims: ...`
- **但 Vector 不会把这个声明写进数据库** → db 里 scope 为空 → 该模块永远不注入

查证：

```sh
# 拉 db 三件套到本地用 sqlite 查（只看 scope 表）
select mid from modules where module_pkg_name='<模块包名>';
select * from scope where mid=<mid>;      -- 空 = 没戏
```

解法 —— **用 `scope set` 整体覆盖，绕过 staticScope 锁**：

```sh
adb shell su -c '/data/adb/lspd/cli scope set <模块包名> <目标包名>/0 <另一个包名>/0'
```

实测有效：`scope set` 成功写入后，软重启即注入（日志出现 `Loaded module ... successfully`）。

> `scope add` 会被拒，`scope set` 能过 —— 这两者的检查逻辑不一样。

### 3. Zygisk 模块不能用 `maps` 判断加载情况

Zygisk 的 so 是通过 **memfd** 加载的，`/proc/<pid>/maps` 里不会出现模块的原始路径，grep 模块名永远是 0 条。这**不代表没加载**。

要确认 Zygisk 侧加载了什么，看：

```sh
adb shell su -c 'cat /data/adb/zygisksu/modules_info'
```

输出形如：

```
zygisk_tcqt    64 fd=8  dev=fe:3d ino=00814889 sz=421888
zygisk_vector  64 fd=9  ...
zygisk_nohello 64 fd=10 ...
```

有记录 = 已加载进 zygote。

---

## LKM 形态的固有代价：`/proc/modules` 对任何 app 可见

这是 late-load / LKM 方案**没法用软件绕开**的一点，但遇到时容易误判成「模块装坏了」。

```sh
$ adb shell cat /proc/modules | grep kernelsu
kernelsu 217088 3 - Live 0x0000000000000000 (O)
```

- `/proc/modules` 权限是 `-r--r--r--`，**任何 app 都能读**
- LKM 模式（`insmod kernelsu.ko`）下，`kernelsu` 在模块列表里**实名可见**
- Nohello / Shamiko / HMA 这类隐藏模块工作在**文件与挂载层**（藏 su、藏 `/data/adb/modules`、藏 mount 点），**改不了内核 `/proc` 的输出**
- 结果：带加固壳（爱加密、网易易盾 VMP 等）的应用扫一眼 `open("/proc/modules")` 就会判定环境异常，直接自杀式崩溃

实测案例：`cn.android.sia.exitentrypermit`（移民局 12367，易盾 VMP）在 root 下启动 3～6 秒必崩，`sp=0 / lr=0 / pc=0x1f4`；同一台机器不 root 时正常运行。Nohello 白名单、denylist、重装全部无效。

**根治只有换 root 形态**：把 KernelSU 编进 boot.img（内核集成模式），那种情况下 `kernelsu` 不存在于 `/proc/modules`。代价是 root 不再随重启消失。

> 顺带一个坑：Nohello 的 `/data/adb/nohello/whitelist` **用空文件创建时会把工作模式切成白名单**，而空名单 = 一个都不隐藏。想要全局隐藏就不要创建这个文件（默认黑名单模式）。

---

## 备查

- `znctl` 即 `/data/adb/modules/zygisksu/bin/zygiskd`，软链在 `/data/adb/ksu/bin/znctl`
- 手动起 ZN daemon（必须 cd 到模块目录，否则报 `fopen ./module.prop.orig failed`）：
  `cd /data/adb/modules/zygisksu && export ZYGISK_ENABLED && ./bin/zygiskd daemon`
- 手动起 vectord（`unshare` 必须用 busybox，toybox 不支持 `--propagation`）：
  `busybox setsid busybox unshare --propagation slave -m /data/adb/modules/zygisk_vector/daemon --system-server-max-retry=3`
- `/data/adb/zygisksu/.abort_msg` 存在时 daemon 会拒绝启动，需先改名（不要删）
- 软重启（`ksud soft-reboot`）**只重跑 `service` 阶段，不会重跑 `post-fs-data`** —— 对「激活」无效；
  而且实测它**会杀掉 `vectord` 且不自动拉起**，跑完 Vector 反而从「已激活」变成「连不上」，必须补一条 `ksud services`
- 只重启 framework 而不动内核：`setprop ctl.restart zygote`（已实测两次，LKM 下 **root 完整保留**，`kernelsu.ko` 仍在 `/proc/modules`，Vector daemon 会自动检测 `System Server died! Clearing caches and re-injecting...` 并重新注入 system_server）

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
| 管理器显示 | `grep -m1 description /data/adb/modules/zygisksu/module.prop` | `[✅zygote, ✅zygote_secondary, Root: ✅KernelSU (32601), ZL]` |
| Vector 状态 | `su -c '/data/adb/lspd/cli status --json'` | `Enabled Modules: 51`（数量随你启用数变化） |
| 真实注入 | 查看 `/data/adb/lspd/log/modules_*.log` | 出现 `VectorLegacyBridge` 的 hook 记录 |

---

## 两个会骗人的输出（别据此判断失败）

- `znctl dump-zn -sa` 里的 `Modules: 0` / `Injected Processes: 0`
  —— 它列的是 module↔service 绑定关系，**不是已加载的 .so**。已实测：这里显示 0 时 `zygisk_vector` 实际注入是成功的。
- `/data/adb/lspd/cli status --json` 的 `isFdAttached: false`
  —— 指 CLI 自己有没有挂上 daemon 的**日志流 fd**，与模块注入无关。

**判断「是否真的激活」只看两处：日志里的 hook 记录 + `Enabled Modules` 计数。**

---

## 备查

- `znctl` 即 `/data/adb/modules/zygisksu/bin/zygiskd`，软链在 `/data/adb/ksu/bin/znctl`
- 手动起 ZN daemon（必须 cd 到模块目录，否则报 `fopen ./module.prop.orig failed`）：
  `cd /data/adb/modules/zygisksu && export ZYGISK_ENABLED && ./bin/zygiskd daemon`
- 手动起 vectord（`unshare` 必须用 busybox，toybox 不支持 `--propagation`）：
  `busybox setsid busybox unshare --propagation slave -m /data/adb/modules/zygisk_vector/daemon --system-server-max-retry=3`
- `/data/adb/zygisksu/.abort_msg` 存在时 daemon 会拒绝启动，需先改名（不要删）
- 软重启（`ksud soft-reboot`）**只重跑 `service` 阶段，不会重跑 `post-fs-data`** —— 对本问题无效

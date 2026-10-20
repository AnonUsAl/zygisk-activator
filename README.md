# Zygisk 一键激活

KernelSU **late-load（越狱）模式**下，一键激活 **Zygisk Next** + **Vector**（JingMatrix 的 LSPosed 续作）的 Android 应用。默认保留 Miuix 风格，并可在设置中切换为 iOS 或 HarmonyOS 风格。

## 它解决什么问题

KernelSU 的 late-load 模式下，内核模块能挂、`su` 能用，但 **Zygisk 注入层不会自己起来**：

- late-load 模式下 `ksud post-fs-data` 会**跳过模块脚本**；
- 只有当 `/data/adb/modules_update/` 里有 staged 内容时，`ksud post-fs-data` 才真正执行模块的 `post-fs-data.sh`；
- 结果：`zn-daemon` 不起、zygote 没被接管、Vector 加载不了 → 所有 Xposed 模块静默失效。

修复就三步：

1. `ksud module install <Zygisk-Next.zip>` —— 把模块内容 stage 到 `modules_update`
2. `ksud post-fs-data` —— 这一次才会真正执行模块脚本
3. `setprop ctl.restart zygote` —— 重启两个 zygote，让注入生效

## 功能

| 功能 | 说明 |
|---|---|
| 一键激活 | 自动把脚本写到 `/data/adb/`，并用 root 跑完整修复流程 |
| 状态清单 | 7 项逐条体检：root / 脚本 / zn-daemon / zygote 接管 / Zygisk 模块加载 / Vector / 注入，一眼看出哪个没起来 |
| 运行日志 | 修复过程实时可见、可复制、可清空 |
| 设置 | 界面风格（Miuix/iOS/HarmonyOS）、深色模式（跟随系统/浅色/深色）、打开时自动检测、清空日志 |

激活任务用 `setsid` 丢进独立会话执行 —— 因为第 3 步会重启 zygote，App 自己会被一起杀掉，但任务不会中断；重开 App 就能从日志里看到完整结果。

## 界面

界面提供 Miuix、iOS 和 HarmonyOS 三种可选风格，默认使用 Miuix。iOS 风格采用大标题、分组列表和悬浮半透明导航；HarmonyOS 风格强调光感材质、层叠卡片和胶囊导航。风格选择与浅色/深色模式相互独立。激活、检测、日志、GhostLock 快捷入口和设置功能保持不变。

iOS 开关控件移植自 [vejei/CupertinoSwitch](https://github.com/vejei/CupertinoSwitch)（MIT）；HarmonyOS 玻璃表面的视觉参数参考 [HOSRookie/ohos_glass_ui](https://github.com/HOSRookie/ohos_glass_ui)（Apache-2.0）。第三方说明见 [`zygisk-activator/THIRD_PARTY_NOTICES.md`](zygisk-activator/THIRD_PARTY_NOTICES.md)。

## 构建

不依赖 Gradle / Android Studio，只用 SDK 里的命令行工具串（`aapt2` → `javac` → `d8` → `zipalign` → `apksigner`），全程离线：

```bash
export ANDROID_HOME=~/Library/Android/sdk
export JAVA_HOME=<temurin-21 路径>
bash zygisk-activator/build.sh
```

产物：`zygisk-activator/zygisk-activator.apk`

## 安装

```bash
adb install -r zygisk-activator.apk
```

首次运行会请求 root，请在 KernelSU 里点「允许」。运行时会往 `/data/adb/` 写三个脚本：

- `/data/adb/fix-zygisk.sh` —— 修复脚本
- `/data/adb/zygisk-run.sh` —— 分离会话包装器（把输出重定向到日志）
- `/data/adb/zygisk-status.sh` —— 只读状态探测

日志落在 `/data/local/tmp/zygisk-run.log`。

## 命令行用法

不装 App 也能用，脚本可以单独跑。

Mac 侧（通过 adb 驱动）：

```bash
bash fix-zygisk.sh             # 执行修复
bash fix-zygisk.sh --verify    # 只读体检，不改任何东西
```

设备侧：

```bash
su -c "sh /data/adb/fix-zygisk.sh"
```

## 注意事项

- 仅适用于 **KernelSU late-load 模式**（内核 root 版本 < 32376、late-load 生效的场景）
- 内存 root 每次重启都会丢，需要先用提权工具重新拿到 root，再点「一键激活」
- 第 3 步会重启 zygote：屏幕黑一下、所有 App 重启，属正常现象
- 需要设备上已有 Zygisk Next 的模块 zip（默认在 `/sdcard/Download/`），脚本会按修改时间自动挑最新的那个

## 许可

MIT

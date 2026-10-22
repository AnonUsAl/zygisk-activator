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
| 有害模块预检 | 激活前先查已知会让 zygote 秒崩的模块（如 `zygisk_nohello`），启用即中止 —— 实测它会让 zygote 启动 1 秒内 SIGSEGV，连带 system_server 与桌面崩溃 |
| sepolicy 应用 | 显式 `ksud sepolicy apply` Vector 的 `sepolicy.rule`。late-load 模式下 post-fs-data 可能跳过它，缺了会让管理器首页显示「Vector 异常」 |
| 等待系统稳定 | zygote 重启后轮询 `boot_completed` + `system_server` + 桌面进程，都到位才继续。**在系统未稳时动 daemon 会把 framework 拖崩、触发整机重启并丢失临时 root** |
| daemon 单例治理 | 检测 vectord 实例数，多实例先清再重建（重复 `ksud services` 会产生多个实例互相抢 binder） |
| dex2oat 兼容 | Android 14+ 的 `dex2oat` 在 APEX 里、`/system/bin/dex2oat` 已不存在，Vector 的 wrapper 挂不上会判 `Dex2oatWrapperBroken`。脚本自动设 `dalvik.vm.dex2oat-flags=--inline-max-code-units=0` 让健康检查通过 |
| Vector daemon 守护 | 第 5 步检测 `vectord` 是否在跑，没起就用 `ksud services` 拉起 —— `service.sh` 里的 `unshare --propagation` 只有 KernelSU 自带 busybox 认得，普通 su 环境解析到 toybox 会直接失败 |
| 状态清单 | 10 项逐条体检：root / 有害模块 / 脚本 / zn-daemon / zygote 接管 / Zygisk 模块加载 / vectord / dex2oat / Vector / 注入 |
| 运行日志 | 修复过程实时可见、可复制、可清空 |
| 设置 | 界面风格（Miuix/iOS/HarmonyOS）、深色模式（跟随系统/浅色/深色）、打开时自动检测、清空日志 |

激活任务用 `setsid` 丢进独立会话执行 —— 因为第 3 步会重启 zygote，App 自己会被一起杀掉，但任务不会中断；重开 App 就能从日志里看到完整结果。

## 界面

界面提供 Miuix、iOS 和 HarmonyOS 三种可选风格，默认使用 Miuix。三种风格共用同一套结构，差别在系统级的设计语言：

- **iOS**：`#F2F2F7` 分组底色、10pt 圆角白卡、缩进到文字起点的发丝分隔线、34pt 大标题、13pt 大写分组标题、蓝色 `#007AFF` 强调色、无底色的导航文字按钮、51×31 的 Cupertino 开关，底部是 49pt 贴底 tab bar。
- **HarmonyOS**：`#F1F3F5` 系统底色、16dp 圆角白卡、5% 黑色发丝线、30sp 大标题、品牌蓝 `#0A59F7` 单色强调、胶囊按钮、状态卡内嵌 4dp 进度条，底部是 56dp 贴底导航条。全局只用「品牌蓝 + 中性灰阶 + 语义绿/红」，不使用渐变或弥散光斑。
- **Miuix**：保持原样的中性圆角卡、KernelSU 绿卡与胶囊按钮。

风格选择与浅色/深色模式相互独立。激活、检测、日志、GhostLock 快捷入口和设置功能保持不变。iOS 开关控件移植自 [vejei/CupertinoSwitch](https://github.com/vejei/CupertinoSwitch)（MIT），第三方说明见 [`zygisk-activator/THIRD_PARTY_NOTICES.md`](zygisk-activator/THIRD_PARTY_NOTICES.md)。

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
- **`ksud soft-reboot` 不要用来干这事**：它只重跑 `service` 阶段、不重跑 `post-fs-data`（激活无效），而且实测会杀掉 `vectord` 且不自动拉起，跑完 Vector 反而从「已激活」变成「连不上」
- 只想重启 framework 又不想动内核（保住内存 root）：`setprop ctl.restart zygote`，LKM 下 root 不丢
- **模块显示 enabled 却不生效**：先查 `verbose_*.log` 有没有 `Loaded module xxx successfully`，再查该模块的 scope 是否为空 —— `staticScope=true` 的模块只能用 `scope set` 覆盖，`scope add` 会被拒
- LKM 形态下 `kernelsu` 在 `/proc/modules` 里对任何 app 可见，Nohello / Shamiko 这类隐藏模块工作在文件与挂载层，**盖不住内核 `/proc` 输出** —— 带加固壳的 App（爱加密、易盾 VMP）会因此直接崩溃，这不是激活没做好

更多排错细节见 [`zygisk-next-vector-激活速查.md`](zygisk-next-vector-激活速查.md)。

## 许可

MIT

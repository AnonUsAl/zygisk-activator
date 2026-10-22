#!/system/bin/sh
#
# 重启后一键激活 Zygisk Next + Vector（设备端版本）
# 环境：Xiaomi lapis / HyperOS / KernelSU late-load + GhostLock(CVE-2026-43499) 临时 root
#
# 用法（必须先有 root）：
#   su -c 'sh /data/adb/fix-zygisk.sh'            # 完整修复
#   su -c 'sh /data/adb/fix-zygisk.sh --verify'   # 只体检，不改动任何东西
#   ZIP=/path/xxx.zip su -c 'sh /data/adb/fix-zygisk.sh'
#
# 前提：已经在 GhostLock 里重新提权拿到 root
#

PATH=/system/bin:/system/xbin:/data/adb/ksu/bin:$PATH
export PATH

KSUD=/data/adb/ksu/bin/ksud
ZNCTL=/data/adb/ksu/bin/znctl
RESETPROP=/data/adb/ksu/bin/resetprop
MODPROP=/data/adb/modules/zygisksu/module.prop
LSPCLI=/data/adb/lspd/cli
VECDIR=/data/adb/modules/zygisk_vector
ZIP="${ZIP:-}"

B=$(printf '\033[1m'); R=$(printf '\033[31m'); G=$(printf '\033[32m')
Y=$(printf '\033[33m'); N=$(printf '\033[0m')

bold() { printf '\n%s%s%s\n' "$B" "$1" "$N"; }
ok()   { printf '  %s✓%s %s\n' "$G" "$N" "$1"; }
bad()  { printf '  %s✗%s %s\n' "$R" "$N" "$1"; }
warn() { printf '  %s!%s %s\n' "$Y" "$N" "$1"; }

# 已知会让 zygote 秒崩的模块（实测：SIGSEGV @ zygisk_nohello/arm64-v8a.so，
# 连带 system_server 与桌面进入 InputChannel 崩溃循环，严重时整机重启、临时 root 丢失）
HARMFUL_MODULES="zygisk_nohello"

# 等系统真正稳定：boot_completed + system_server + 桌面进程都到位。
# 这一步是硬要求 —— zygote 刚重启、系统还在恢复时去动 daemon，会把 framework 拖崩并触发整机重启。
wait_stable() {
  ws_i=0
  while [ "$ws_i" -lt 60 ]; do
    ws_bc=$(getprop sys.boot_completed 2>/dev/null)
    ws_ss=$(pidof system_server 2>/dev/null)
    ws_hm=$(pidof com.miui.home 2>/dev/null)
    if [ "$ws_bc" = "1" ] && [ -n "$ws_ss" ] && [ -n "$ws_hm" ]; then
      return 0
    fi
    sleep 2
    ws_i=$((ws_i + 2))
  done
  return 1
}

vectord_count() {
  pidof vectord 2>/dev/null | wc -w | tr -d ' '
}

VERIFY_ONLY=0
[ "${1:-}" = "--verify" ] && VERIFY_ONLY=1

# ------------------------------------------------------------- 0. root 检查
bold "0/8  root 检查"

if [ "$(id -u)" != "0" ]; then
  bad "当前不是 root"
  echo "      → 先跑：su -c 'sh /data/adb/fix-zygisk.sh'"
  echo "      → 如果连 su 都没有：打开 GhostLock (com.ghostlock.app) 重新提权"
  exit 1
fi
ok "root 正常：$(id)"

# ------------------------------------------------------- 0.5 有害模块预检
bold "0.5/8  有害模块预检"
harmful_hit=""
for m in $HARMFUL_MODULES; do
  if [ -d "/data/adb/modules/$m" ] && [ ! -f "/data/adb/modules/$m/disable" ]; then
    harmful_hit="$harmful_hit $m"
  fi
done
if [ -n "$harmful_hit" ]; then
  bad "以下模块处于启用状态，会让 zygote 秒崩：$harmful_hit"
  echo "      → 实测它们会在 zygote 启动 1 秒内 SIGSEGV，"
  echo "        连带 system_server / 桌面崩溃，最终可能整机重启、临时 root 丢失"
  echo "      → 处置：touch /data/adb/modules/<模块名>/disable 后重跑本脚本"
  [ "$VERIFY_ONLY" = 0 ] && exit 1
else
  ok "没有已知有害模块处于启用状态"
fi

if [ "$VERIFY_ONLY" = 0 ]; then
  # ----------------------------------------------------------- 1. 安装包
  bold "1/8  定位模块安装包"
  if [ -z "$ZIP" ]; then
    # 按修改时间取最新（不能用字母序：1.5.1 会排在 1.5.2 后面）
    ZIP=$(ls -1t /sdcard/Download/*.zip 2>/dev/null | grep -i zygisk | head -1)
  fi
  if [ -z "$ZIP" ] || [ ! -f "$ZIP" ]; then
    bad "找不到 Zygisk Next 安装包"
    echo "      → 下载 Zygisk Next 放到 /sdcard/Download/，"
    echo "        或指定：ZIP=/path/to/xxx.zip su -c 'sh /data/adb/fix-zygisk.sh'"
    exit 1
  fi
  ok "使用安装包：$ZIP"

  # ----------------------------------------------------------- 2. 暂存
  bold "2/8  重新暂存模块（关键前提）"
  warn "暂存区为空时 ksud 会整体跳过模块脚本 —— 这一步不能省"
  out=$("$KSUD" module install "$ZIP" 2>&1)
  case "$out" in
    *rror*|*"not found"*|*"No such"*) bad "暂存失败：$out"; exit 1 ;;
  esac
  pending=$(ls -A /data/adb/modules_update/ 2>/dev/null)
  if [ -z "$pending" ]; then
    bad "暂存区仍为空 —— post-fs-data 不会生效，中止"
    exit 1
  fi
  ok "已暂存：$(echo "$pending" | tr '\n' ' ')"

  # ----------------------------------------------------------- 3. post-fs-data
  bold "3/8  触发 post-fs-data"
  "$KSUD" post-fs-data >/dev/null 2>&1
  sleep 3
  if ps -A -o NAME 2>/dev/null | grep -q zn-daemon; then
    ok "zn-daemon 已起来"
  else
    bad "zn-daemon 没起来"
    echo "      → 检查 /data/adb/zygisksu/.abort_msg 是否存在，存在就改名（不要删）后重跑"
    exit 1
  fi

  # ----------------------------------------------------------- 4. sepolicy
  bold "4/8  应用 Vector 的 sepolicy"
  # Vector 用 SELinux 检查 dex2oat 对 dex2oat_exec 的 execute_no_trans 来判断 sepolicy 是否加载，
  # 缺了它管理器首页就会显示「Vector 异常」（Degraded）。
  # late-load 模式下 post-fs-data 可能没处理 sepolicy.rule，所以这里显式 apply 一次。
  if [ -f "$VECDIR/sepolicy.rule" ]; then
    if "$KSUD" sepolicy apply "$VECDIR/sepolicy.rule" >/dev/null 2>&1; then
      ok "sepolicy.rule 已应用（$(wc -l < "$VECDIR/sepolicy.rule" 2>/dev/null | tr -d ' ') 行）"
    else
      warn "sepolicy apply 失败（可能已应用过），继续"
    fi
  else
    warn "没找到 $VECDIR/sepolicy.rule，跳过"
  fi

  # ----------------------------------------------------------- 5. 接管 zygote
  bold "5/8  重启两个 zygote，让 Zygisk 接管"
  setprop ctl.restart zygote
  setprop ctl.restart zygote_secondary
  warn "system_server 会一并重启，屏幕黑一下属正常现象"
  echo "      → 等待系统稳定（boot_completed + system_server + 桌面都到位）"
  if wait_stable; then
    ok "系统已稳定"
  else
    bad "等了 60 秒系统仍未稳定"
    echo "      → 不要再继续操作，先确认手机屏幕是否正常"
    exit 1
  fi
fi

# ------------------------------------------- 6. Vector daemon 单例 + dex2oat
bold "6/8  Vector daemon 与 dex2oat 配置"

# 6.1 daemon 单例：重复执行 ksud services 会产生多个 vectord，彼此抢 binder 导致 CLI 连不上
cnt=$(vectord_count)
if [ "$cnt" -gt 1 ]; then
  warn "发现 $cnt 个 vectord 实例，清理后重建"
  for p in $(pidof vectord 2>/dev/null); do kill "$p" 2>/dev/null; done
  sleep 2
fi

if [ "$VERIFY_ONLY" = 0 ]; then
  # 6.2 拉起：service.sh 里的 unshare --propagation 只有 KernelSU 自带 busybox 认得，
  #      toybox 的 unshare 不支持该参数，所以不能手动跑 service.sh，必须走 ksud services。
  if [ "$(vectord_count)" = "0" ] || [ "$cnt" -gt 1 ]; then
    "$KSUD" services >/dev/null 2>&1
    sleep 8
  fi
  cnt=$(vectord_count)
  if [ "$cnt" = "1" ]; then
    ok "vectord 单实例运行中（pid $(pidof vectord 2>/dev/null)）"
  elif [ "$cnt" = "0" ]; then
    bad "vectord 没起来"
    echo "      → 只能靠 ksud services 拉起；别手动跑 service.sh（toybox unshare 不支持 --propagation）"
  else
    bad "vectord 有 $cnt 个实例，请重启后再跑一次本脚本"
  fi

  # 6.3 dex2oat inlining：Android 14+ 把 dex2oat 挪进 APEX（/apex/com.android.art/bin/dex2oat64），
  #     /system/bin/dex2oat 已不存在 → Vector 的 dex2oat wrapper 挂不上，
  #     管理器会判 Dex2oatWrapperBroken 并显示「Vector 异常」。
  #     补上这个属性让 isDex2OatInliningDisabled() 返回 true，健康检查即可通过。
  cur_flags=$(getprop dalvik.vm.dex2oat-flags 2>/dev/null)
  case "$cur_flags" in
    *--inline-max-code-units=0*)
      ok "dex2oat inlining 已禁用（$cur_flags）" ;;
    *)
      "$RESETPROP" -- dalvik.vm.dex2oat-flags --inline-max-code-units=0 >/dev/null 2>&1
      now_flags=$(getprop dalvik.vm.dex2oat-flags 2>/dev/null)
      case "$now_flags" in
        *--inline-max-code-units=0*) ok "已设置 dex2oat inlining 关闭（$now_flags）" ;;
        *) warn "dex2oat 属性设置失败，管理器可能仍显示「Vector 异常」（不影响模块功能）" ;;
      esac ;;
  esac
else
  if [ "$cnt" = "1" ]; then ok "vectord 单实例"; else bad "vectord 实例数：$cnt"; fi
  cur_flags=$(getprop dalvik.vm.dex2oat-flags 2>/dev/null)
  case "$cur_flags" in
    *--inline-max-code-units=0*) ok "dex2oat inlining 已禁用" ;;
    *) warn "dex2oat inlining 未禁用 → 管理器可能显示「Vector 异常」" ;;
  esac
fi

# ------------------------------------------------------------- 7. 验收
bold "7/8  验收"

st=$("$ZNCTL" status 2>&1)
echo "$st" | grep -E 'zygote_states|inject_state|modules64|modules32|modules_with_issue' | sed 's/^/     /'

sleep 3
desc=$(grep -m1 description "$MODPROP" 2>/dev/null)
printf '     %s\n' "$desc"

vec=$("$LSPCLI" status --json 2>/dev/null | grep -E 'Framework Version|Version Code|Enabled Modules')
if [ -z "$vec" ]; then
  bad "Vector: cli 连不上 daemon（Connection refused）"
else
  printf '     Vector: %s\n' "$(echo "$vec" | tr '\n' ' ')"
fi

vec_cnt=$(vectord_count)
printf '     vectord 实例数: %s\n' "$vec_cnt"

echo
if echo "$st" | grep -q 'zygote_states:2' && echo "$desc" | grep -q 'zygote_secondary' && [ -n "$vec" ] && [ "$vec_cnt" = "1" ]; then
  ok "全部激活 ✅"
  echo "     zygote_states:2 · zygisk_vector 已加载 · vectord 单实例"
  echo "     确认自己的模块真的在跑：grep -a 'Loaded module' /data/adb/lspd/log/verbose_*.log | tail"
else
  bad "还没完全激活，把上面的输出发我"
fi

cat <<'NOTE'

  两个会骗人的输出（别据此判断失败）：
    · znctl dump-zn -sa 里的 Modules: 0 / Injected Processes: 0
        → 它列的是 module↔service 绑定，不是已加载的 .so
    · Vector status 里的 isFdAttached: false
        → 指 CLI 有没有挂上日志流 fd，与模块注入无关
  判断是否真的激活，只看日志里的 "Loaded module ... successfully" + Enabled Modules 计数。

  几个今天踩出来的坑：
    · ksud soft-reboot 只重跑 service 阶段、不重跑 post-fs-data，对激活无效；
      它还会杀掉 vectord 且不自动拉起 —— 用完必须补 ksud services
    · 只重启 framework 不动内核：setprop ctl.restart zygote（LKM 下 root 不丢）
    · zygote 刚重启时不要立刻动 daemon，等系统稳定（本脚本已内置等待）
    · 模块 enabled 但不生效：先看 verbose 日志有没有 Loaded module，再查 scope 是否为空
      （staticScope 模块要用 scope set 覆盖，scope add 会被拒）
    · Vector 首页显示「异常」= Degraded，是 sepolicy / system_server 注入 / dex2oat 三项之一，
      不影响模块功能；本脚本 4/8 与 6.3 已分别处理前两项和第三项
NOTE

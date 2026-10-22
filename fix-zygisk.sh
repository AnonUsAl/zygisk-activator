#!/usr/bin/env bash
#
# 重启后一键激活 Zygisk Next + Vector
# 适用环境：Xiaomi lapis / HyperOS / KernelSU late-load + GhostLock（CVE-2026-43499）临时 root
#
# 用法：
#   bash fix-zygisk.sh            # 完整流程（会重启 zygote，system_server 会跟着重启）
#   bash fix-zygisk.sh --verify   # 只体检，不改动设备任何东西
#   ZIP=/path/xxx.zip bash fix-zygisk.sh   # 手动指定安装包
#
# 前提：手机已插上 USB、USB 调试已开、并且已经跑过 GhostLock 拿到 root
#
set -uo pipefail

ZIP="${ZIP:-}"
KSUD=/data/adb/ksu/bin/ksud
ZNCTL=/data/adb/ksu/bin/znctl
MODPROP=/data/adb/modules/zygisksu/module.prop
LSPCLI=/data/adb/lspd/cli

bold() { printf '\n\033[1m%s\033[0m\n' "$1"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$1"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$1"; }

sq()   { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }
sush() { adb shell "su -c $(sq "$1")" 2>&1 | tr -d '\r'; }
nosh() { adb shell "$1" 2>&1 | tr -d '\r'; }

VERIFY_ONLY=0
[ "${1:-}" = "--verify" ] && VERIFY_ONLY=1

# ---------------------------------------------------------------- 0. 前置检查
bold "0/6  设备与 root 检查"

if ! adb get-state >/dev/null 2>&1; then
  bad "没有检测到 adb 设备：先插上手机、确认 USB 调试已开"
  exit 1
fi
ok "设备：$(nosh 'getprop ro.product.model')  /  Android $(nosh 'getprop ro.build.version.release')"

if ! sush 'id' | grep -q 'uid=0'; then
  bad "当前没有 root"
  echo "      → 手机上打开 GhostLock（com.ghostlock.app），重新提权后再跑本脚本"
  exit 1
fi
ok "root 正常：$(sush 'id')"

if [ "$VERIFY_ONLY" = 0 ]; then
  # -------------------------------------------------------------- 1. 安装包
  bold "1/6  定位模块安装包"
  if [ -z "$ZIP" ]; then
    # 按修改时间取最新的 Zygisk 包（注意不能用字母序，1.5.1 会排在 1.5.2 后面）
    ZIP=$(sush 'ls -1t /sdcard/Download/*.zip 2>/dev/null' | grep -i 'zygisk' | head -1)
  fi
  if [ -z "$ZIP" ] || sush "ls \"$ZIP\"" | grep -q 'No such file'; then
    bad "找不到 Zygisk Next 安装包"
    echo "      → 下载 Zygisk Next v1.5.2 放到 /sdcard/Download/，"
    echo "        或手动指定：ZIP=/path/to/xxx.zip bash fix-zygisk.sh"
    exit 1
  fi
  ok "使用安装包：$ZIP"

  # -------------------------------------------------------------- 2. 暂存
  bold "2/6  重新暂存模块（关键前提）"
  warn "暂存区为空时 ksud 会整体跳过模块脚本 —— 所以这一步不能省"
  out=$(sush "$KSUD module install '$ZIP'")
  case "$out" in
    *"error"*|*"Error"*|*"not found"*|*"No such"*) bad "暂存失败：$out"; exit 1 ;;
  esac
  pending=$(sush 'ls -A /data/adb/modules_update/')
  if [ -z "$pending" ]; then
    bad "暂存区仍为空 —— 后面 post-fs-data 不会生效，中止"
    exit 1
  fi
  ok "已暂存：$(echo "$pending" | tr '\n' ' ')"

  # -------------------------------------------------------------- 3. post-fs-data
  bold "3/6  触发 post-fs-data"
  sush "$KSUD post-fs-data >/dev/null 2>&1"
  sleep 3
  if sush 'ps -A' | grep -q 'zn-daemon'; then
    ok "zn-daemon 已起来"
  else
    bad "zn-daemon 没起来"
    echo "      → 检查 /data/adb/zygisksu/.abort_msg 是否存在，存在就改名（不要删）后重跑"
    exit 1
  fi

  # -------------------------------------------------------------- 4. 接管 zygote
  bold "4/6  重启两个 zygote，让 Zygisk 接管"
  sush 'setprop ctl.restart zygote'
  sush 'setprop ctl.restart zygote_secondary'
  warn "system_server 会一并重启，屏幕黑一下属正常现象，等 25 秒"
  sleep 25
fi

# --------------------------------------------------- 5. Vector daemon（vectord）
bold "5/6  确保 Vector daemon 在跑"
# vectord 不会自己起来：service.sh 里的 unshare 只有 busybox 版本认得 --propagation，
# 普通 su 环境会解析到 toybox 的 unshare 并报 Unknown option。
# 唯一可靠的办法是走 ksud services（它的 PATH 里带 /data/adb/ksu/bin/busybox）。
if sush 'ps -A' | grep -q 'vectord'; then
  ok "vectord 已在运行"
else
  warn "vectord 没起来（此时 cli status 会报 Connection refused），用 ksud services 拉起"
  sush "$KSUD services >/dev/null 2>&1"
  sleep 8
  if sush 'ps -A' | grep -q 'vectord'; then
    ok "vectord 已拉起"
  else
    bad "vectord 仍未启动"
    echo "      → 别手动跑 service.sh / unshare：toybox 的 unshare 不支持 --propagation，一定失败"
    echo "        只能靠 ksud services"
  fi
fi

# ---------------------------------------------------------------- 6. 验收
bold "6/6  验收"

st=$(sush "$ZNCTL status")
echo "$st" | grep -E 'zygote_states|inject_state|modules64|modules32|modules_with_issue' | sed 's/^/     /'

sleep 5
desc=$(sush "grep -m1 description $MODPROP")
printf '     %s\n' "$desc"

if sush 'ps -A' | grep -q 'vectord'; then
  printf '     vectord: 运行中\n'
else
  printf '     vectord: \033[31m未运行\033[0m（跑 ksud services）\n'
fi

vec=$(sush "$LSPCLI status --json" | grep -E 'Framework Version|Version Code|Enabled Modules')
if [ -z "$vec" ]; then
  bad "Vector: cli status 连不上 daemon（Connection refused），先跑 ksud services"
else
  printf '     Vector: %s\n' "$(echo "$vec" | tr '\n' ' ')"
fi

echo
if echo "$st" | grep -q 'zygote_states:2' && echo "$desc" | grep -q 'zygote_secondary' && [ -n "$vec" ]; then
  ok "全部激活 ✅"
  echo "     zygote_states:2 · zygisk_vector 已加载 · vectord 在跑"
  echo "     想确认自己的模块真的在跑：adb shell \"su -c 'grep -a \\\"Loaded module\\\" /data/adb/lspd/log/verbose_*.log | tail'\""
else
  bad "还没完全激活，把上面的输出发我"
fi

# ---------------------------------------------------------------- 备注
cat <<'NOTE'

  两个会骗人的输出（别据此判断失败）：
    · znctl dump-zn -sa 里的 Modules: 0 / Injected Processes: 0
        → 它列的是 module↔service 绑定，不是已加载的 .so
    · Vector status 里的 isFdAttached: false
        → 指 CLI 有没有挂上日志流 fd，与模块注入无关
  判断是否真的激活，只看日志里的 VectorLegacyBridge hook 记录 + Enabled Modules 计数。

  另外：
    · ksud soft-reboot 对本激活流程无效（它只重跑 service 阶段，不会重跑 post-fs-data）；
      而且实测它会杀掉 vectord 且不自动拉起 —— 用完必须补 ksud services，否则 Vector 反而连不上。
    · 只想重启 framework、不动内核：setprop ctl.restart zygote（LKM 下 root 不丢，已实测两次）。
    · 模块显示 enabled 但完全没反应：
        1) 先看 verbose 日志里有没有 "Loaded module xxx successfully"
        2) 再看 scope 是否为空 —— staticScope 模块只能用 scope set 覆盖，scope add 会被拒
        3) Zygisk 的 so 走 memfd，maps 里 grep 不到属正常，要看 /data/adb/zygisksu/modules_info
NOTE

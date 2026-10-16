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
MODPROP=/data/adb/modules/zygisksu/module.prop
LSPCLI=/data/adb/lspd/cli
ZIP="${ZIP:-}"

B=$(printf '\033[1m'); R=$(printf '\033[31m'); G=$(printf '\033[32m')
Y=$(printf '\033[33m'); N=$(printf '\033[0m')

bold() { printf '\n%s%s%s\n' "$B" "$1" "$N"; }
ok()   { printf '  %s✓%s %s\n' "$G" "$N" "$1"; }
bad()  { printf '  %s✗%s %s\n' "$R" "$N" "$1"; }
warn() { printf '  %s!%s %s\n' "$Y" "$N" "$1"; }

VERIFY_ONLY=0
[ "${1:-}" = "--verify" ] && VERIFY_ONLY=1

# ------------------------------------------------------------- 0. root 检查
bold "0/5  root 检查"

if [ "$(id -u)" != "0" ]; then
  bad "当前不是 root"
  echo "      → 先跑：su -c 'sh /data/adb/fix-zygisk.sh'"
  echo "      → 如果连 su 都没有：打开 GhostLock (com.ghostlock.app) 重新提权"
  exit 1
fi
ok "root 正常：$(id)"

if [ "$VERIFY_ONLY" = 0 ]; then
  # ----------------------------------------------------------- 1. 安装包
  bold "1/5  定位模块安装包"
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
  bold "2/5  重新暂存模块（关键前提）"
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
  bold "3/5  触发 post-fs-data"
  "$KSUD" post-fs-data >/dev/null 2>&1
  sleep 3
  if ps -A -o NAME 2>/dev/null | grep -q zn-daemon; then
    ok "zn-daemon 已起来"
  else
    bad "zn-daemon 没起来"
    echo "      → 检查 /data/adb/zygisksu/.abort_msg 是否存在，存在就改名（不要删）后重跑"
    exit 1
  fi

  # ----------------------------------------------------------- 4. 接管 zygote
  bold "4/5  重启两个 zygote，让 Zygisk 接管"
  setprop ctl.restart zygote
  setprop ctl.restart zygote_secondary
  warn "system_server 会一并重启，屏幕黑一下属正常现象，等 25 秒"
  sleep 25
fi

# ------------------------------------------------------------- 5. 验收
bold "5/5  验收"

st=$("$ZNCTL" status 2>&1)
echo "$st" | grep -E 'zygote_states|inject_state|modules64|modules32|modules_with_issue' | sed 's/^/     /'

sleep 5
desc=$(grep -m1 description "$MODPROP" 2>/dev/null)
printf '     %s\n' "$desc"

vec=$("$LSPCLI" status --json 2>/dev/null | grep -E 'Framework Version|Version Code|Enabled Modules')
printf '     Vector: %s\n' "$(echo "$vec" | tr '\n' ' ')"

echo
if echo "$st" | grep -q 'zygote_states:2' && echo "$desc" | grep -q 'zygote_secondary'; then
  ok "全部激活 ✅"
  echo "     zygote_states:2 · zygisk_vector 已加载"
  echo "     确认自己的模块真的在跑：tail -20 /data/adb/lspd/log/modules_*.log"
else
  bad "还没完全激活，把上面的输出发我"
fi

cat <<'NOTE'

  两个会骗人的输出（别据此判断失败）：
    · znctl dump-zn -sa 里的 Modules: 0 / Injected Processes: 0
        → 它列的是 module↔service 绑定，不是已加载的 .so
    · Vector status 里的 isFdAttached: false
        → 指 CLI 有没有挂上日志流 fd，与模块注入无关
  判断是否真的激活，只看日志里的 VectorLegacyBridge hook 记录 + Enabled Modules 计数。

  另外：ksud soft-reboot 对本问题无效（它只重跑 service 阶段，不会重跑 post-fs-data）。
NOTE

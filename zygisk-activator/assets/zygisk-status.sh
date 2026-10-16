#!/system/bin/sh
#
# 逐项探测激活状态，输出机器可读清单。
# 每行格式： key|state|detail
#   state: 1 = 已激活 / 0 = 未激活
#
PATH=/system/bin:/system/xbin:/data/adb/ksu/bin:$PATH
export PATH

ZNCTL=/data/adb/ksu/bin/znctl
LSPCLI=/data/adb/lspd/cli

# ---------------------------------------------------------------- root
if [ "$(id -u)" = "0" ]; then
  echo "root|1|uid=0(root) · KernelSU"
else
  echo "root|0|没有 root，先去 GhostLock 提权"
fi

# ---------------------------------------------------------------- 脚本
if [ -f /data/adb/fix-zygisk.sh ] && [ -f /data/adb/zygisk-run.sh ]; then
  echo "script|1|/data/adb/fix-zygisk.sh 已就位"
else
  echo "script|0|运行脚本缺失"
fi

# ---------------------------------------------------------------- Zygisk Next daemon
ZNP=$(pidof zn-daemon 2>/dev/null)
if [ -n "$ZNP" ]; then
  echo "zn|1|zn-daemon PID $ZNP"
else
  echo "zn|0|zn-daemon 未运行"
fi

# ---------------------------------------------------------------- 从 znctl status 取
ST=$("$ZNCTL" status 2>/dev/null)
if [ -z "$ST" ]; then
  echo "zygote|0|znctl 无应答"
  echo "znmod|0|模块注册表不可读"
else
  ZS=$(echo "$ST" | grep 'zygote_states:' | head -1 | sed 's/^zygote_states://' | tr -d ' ')
  if [ "$ZS" = "2" ]; then
    echo "zygote|1|zygote + zygote_secondary"
  elif [ -n "$ZS" ] && [ "$ZS" != "0" ]; then
    echo "zygote|0|zygote_states=$ZS（应为 2）"
  else
    echo "zygote|0|zygote 未被接管"
  fi

  M64=$(echo "$ST" | grep 'modules64:' | head -1 | sed 's/^modules64:[0-9]*,//' | tr -d ' ')
  case "$M64" in
    *zygisk_vector*) echo "znmod|1|$M64" ;;
    *)               echo "znmod|0|未加载 zygisk_vector" ;;
  esac
fi

# ---------------------------------------------------------------- Vector 框架
VJ=$("$LSPCLI" status --json 2>/dev/null)
VN=$(echo "$VJ" | grep 'Enabled Modules' | head -1 | tr -dc '0-9')
if [ -n "$VN" ]; then
  echo "vector|1|Framework 已启用 $VN 个模块"
else
  echo "vector|0|Vector CLI 无应答"
fi

# ---------------------------------------------------------------- Vector 注入活动
CNT=$(cat /data/adb/lspd/log/modules_*.log 2>/dev/null | grep -ac 'VectorLegacyBridge')
[ -z "$CNT" ] && CNT=0
if [ "$CNT" -gt 0 ]; then
  echo "inject|1|本次日志 $CNT 条 hook 记录"
else
  echo "inject|0|本次日志暂无注入记录"
fi

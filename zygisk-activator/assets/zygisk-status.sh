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

# ---------------------------------------------------------------- 有害模块
# 实测 zygisk_nohello 会让 zygote 启动 1 秒内 SIGSEGV，连带 system_server 与桌面崩溃，
# 严重时整机重启、临时 root 丢失。启用即判为不健康。
HARMFUL=""
for m in zygisk_nohello; do
  if [ -d "/data/adb/modules/$m" ] && [ ! -f "/data/adb/modules/$m/disable" ]; then
    HARMFUL="$HARMFUL $m"
  fi
done
if [ -z "$HARMFUL" ]; then
  echo "harmful|1|无已知有害模块"
else
  echo "harmful|0|已启用:$HARMFUL（会让 zygote 秒崩）"
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

# ---------------------------------------------------------------- Vector daemon
# vectord 不会自己起来：service.sh 里的 unshare 只有 KernelSU 自带 busybox 认得
# --propagation，普通 su 环境解析到 toybox 的 unshare 会报 Unknown option。
# 没有 vectord 时 Vector CLI 一律 Connection refused，所有模块静默失效。
# 多个实例会互相抢 binder，也必须报出来。
VDPS=$(pidof vectord 2>/dev/null)
VDN=$(echo "$VDPS" | wc -w | tr -d ' ')
if [ "$VDN" = "1" ]; then
  echo "vectord|1|vectord PID $VDPS"
elif [ "$VDN" = "0" ]; then
  echo "vectord|0|vectord 未运行 · 跑 ksud services 拉起"
else
  echo "vectord|0|有 $VDN 个实例，冲突（重启后再激活）"
fi

# ---------------------------------------------------------------- dex2oat
# Android 14+ dex2oat 在 APEX 里，/system/bin/dex2oat 不存在 → Vector 的 wrapper 挂不上，
# 管理器会判 Dex2oatWrapperBroken 显示「异常」，除非这个属性把 inlining 禁掉。
D2F=$(getprop dalvik.vm.dex2oat-flags 2>/dev/null)
case "$D2F" in
  *--inline-max-code-units=0*) echo "dex2oat|1|inlining 已关闭" ;;
  *) echo "dex2oat|0|属性未设，Vector 可能显示「异常」（不影响功能）" ;;
esac

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

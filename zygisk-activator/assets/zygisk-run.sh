#!/system/bin/sh
#
# 由 Zygisk 一键激活 App 放置的分离式启动包装脚本。
# 作用：把 fix-zygisk.sh 放到独立会话里跑，并把输出落到一个日志文件，
#       这样即使 App 因为 zygote 重启被杀掉，任务依然能跑完，日志也不会丢。
#
LOG=/data/local/tmp/zygisk-run.log

: > "$LOG"
chmod 644 "$LOG"
echo "===BEGIN $(date '+%H:%M:%S')===" >> "$LOG"
sh /data/adb/fix-zygisk.sh "$@" >> "$LOG" 2>&1
echo "===DONE===" >> "$LOG"
chmod 644 "$LOG"

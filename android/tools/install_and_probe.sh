#!/usr/bin/env bash
# 安装 APK 到 vivo 真机并自动处理「安全守护」安装确认弹窗，然后运行探针抓取日志。
#
# 背景：vivo 对「未知来源」应用安装会弹出 PackageInterceptActivity，
#       需要先勾选「已了解应用的风险检测结果」再点「继续安装」，
#       否则 adb install 会静默挂死（无任何输出/错误码）。
#
# 用法: ./tools/install_and_probe.sh [device_serial]
set -uo pipefail

DEV="${1:-10AFC81DYR000CP}"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
PKG="com.vivo.lpa"
APK="$(cd "$(dirname "$0")/.." && pwd)/app/build/outputs/apk/debug/app-debug.apk"
OUT="${OUT:-/tmp/probe.txt}"

[ -f "$APK" ] || { echo "APK 不存在: $APK"; exit 1; }

# 必须先卸载：否则后面「包是否存在」的成功判据会被上一次的旧版本满足，
# 导致弹窗还没点完就误判为安装成功，实际装的仍是旧 APK。
echo "[1/4] 卸载旧版本"
"$ADB" -s "$DEV" uninstall "$PKG" >/dev/null 2>&1 || true

echo "[1/4] 安装 $APK -> $DEV"
( timeout 240 "$ADB" -s "$DEV" install -r -g "$APK" >/tmp/lpa_install.out 2>&1 & )

# 轮询等待确认弹窗出现；出现则用 uiautomator 定位控件后点击（不硬编码坐标）
for i in $(seq 1 20); do
    sleep 3
    # 注意: dumpsys window 会输出多行 mCurrentFocus, 第一行常为 null,
    # 因此不能用 grep -m1, 必须全局匹配 Activity 名
    focus=$("$ADB" -s "$DEV" shell dumpsys window 2>/dev/null \
            | grep -E "mCurrentFocus|mFocusedApp" | tr '\n' ' ')
    case "$focus" in
        *PackageInterceptActivity*|*packageinstaller*)
            echo "[2/4] 检测到安装确认弹窗，解析控件坐标"
            "$ADB" -s "$DEV" shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
            "$ADB" -s "$DEV" pull /data/local/tmp/ui.xml /tmp/ui.xml >/dev/null 2>&1
            python3 - <<'PY' > /tmp/taps.txt
import re
try:
    s = open('/tmp/ui.xml', encoding='utf-8').read()
except Exception:
    raise SystemExit
def center(b):
    x1, y1, x2, y2 = map(int, re.findall(r'-?\d+', b))
    return (x1 + x2) // 2, (y1 + y2) // 2
cb = btn = None
for m in re.finditer(r'<node[^>]*>', s):
    t = m.group(0)
    rid = (re.search(r'resource-id="([^"]*)"', t) or [None, ''])[1] if re.search(r'resource-id="([^"]*)"', t) else ''
    bnd = (re.search(r'bounds="([^"]*)"', t) or [None, ''])[1] if re.search(r'bounds="([^"]*)"', t) else ''
    chk = re.search(r'checked="(\w+)"', t)
    if not bnd:
        continue
    if rid.endswith('deleted_file_state_cb') and chk and chk.group(1) == 'false':
        cb = center(bnd)
    if rid == 'android:id/button1':
        btn = center(bnd)
if cb:
    print('CB %d %d' % cb)
if btn:
    print('BTN %d %d' % btn)
PY
            while read -r kind x y; do
                echo "      tap $kind ($x,$y)"
                "$ADB" -s "$DEV" shell input tap "$x" "$y"
                sleep 2
            done < /tmp/taps.txt
            ;;
    esac
    if "$ADB" -s "$DEV" shell pm list packages 2>/dev/null | grep -q "$PKG"; then
        echo "[2/4] 安装完成"
        break
    fi
done

"$ADB" -s "$DEV" shell pm list packages 2>/dev/null | grep -q "$PKG" || {
    echo "安装失败，install 输出："; cat /tmp/lpa_install.out; exit 1; }

echo "[3/4] 启动探针"
"$ADB" -s "$DEV" logcat -c
"$ADB" -s "$DEV" shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1
sleep 15

echo "[4/4] 抓取 LPA_PROBE 日志 -> $OUT"
"$ADB" -s "$DEV" logcat -d -s LPA_PROBE 2>/dev/null \
  | sed -E 's/^[0-9-]+ +[0-9:.]+ +[0-9]+ +[0-9]+ +[A-Z] +LPA_PROBE *: ?//' > "$OUT"
echo "共 $(wc -l < "$OUT") 行"

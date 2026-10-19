#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
PKG="com.vivo.lpa"
MODEL_SRC="/data/local/tmp/lpa-models"
MODEL_DST="/data/data/${PKG}/files/models"
DB_SRC="/data/user/0/${PKG}/databases/live_photo_agent.db"
DB_BACKUP="/data/local/tmp/live_photo_agent.db.bak"

echo "=== Deploy Live Photo Agent ==="

adb wait-for-device
adb root 2>/dev/null || true

echo "1. Backup existing VLM database..."
adb shell "test -f '$DB_SRC' && cp '$DB_SRC' '$DB_BACKUP' 2>/dev/null && echo '  backed up' || echo '  no existing db'"

echo "2. Uninstall old APK..."
adb uninstall "$PKG" 2>/dev/null || true
sleep 1

echo "3. Install new APK..."
adb install --no-streaming -r -g "$APK"

echo "4. Restore VLM database..."
UID_DIR=$(adb shell "ls -d /data/user/0/${PKG} 2>/dev/null" | head -1)
if [ -n "$UID_DIR" ]; then
    DB_NEW="/data/user/0/${PKG}/databases/live_photo_agent.db"
    adb shell "mkdir -p \$(dirname '$DB_NEW') && test -f '$DB_BACKUP' && cp '$DB_BACKUP' '$DB_NEW' && chmod 660 '$DB_NEW' && echo '  restored' || echo '  no backup to restore'"
fi

echo "5. Deploy models..."
# Not UID: bash treats that as a readonly variable, so assigning it aborts the script.
APP_UID=$(adb shell "stat -c '%U' /data/data/${PKG} 2>/dev/null" | tr -d '\r')
adb shell "
    mkdir -p '$MODEL_DST' 2>/dev/null
    tar cf - -C '$MODEL_SRC' . | tar xf - -C '$MODEL_DST' 2>/dev/null
    chown -R '${APP_UID}:${APP_UID}' '$MODEL_DST' 2>/dev/null
    mkdir -p '$MODEL_DST/qwen/runtime/dsp/cdsp' '$MODEL_DST/vlm/runtime/dsp/cdsp' 2>/dev/null
    ln -sf '$MODEL_DST/qwen/runtime/dsp/libQnnHtpV81Skel.so' '$MODEL_DST/qwen/runtime/dsp/cdsp/' 2>/dev/null
    ln -sf '$MODEL_DST/vlm/runtime/dsp/libQnnHtpV81Skel.so' '$MODEL_DST/vlm/runtime/dsp/cdsp/' 2>/dev/null
    test -f '$MODEL_DST/vlm/genie_config.json' && echo '  models OK' || echo '  models FAILED'
"

echo "6. Restore database ownership..."
# Reinstalling changes the app UID, but the backup is copied back as root. Without this the
# app cannot open its own database and every session looks empty.
adb shell "
    chown -R '${APP_UID}:${APP_UID}' '$(dirname "$DB_SRC")' 2>/dev/null
    chmod 771 '$(dirname "$DB_SRC")' 2>/dev/null
    chmod 660 '$DB_SRC' 2>/dev/null
    echo '  ownership ${APP_UID}'
"

echo "7. Launch app..."
adb shell "am clear-debug-app '$PKG' 2>/dev/null; am start -W -n '$PKG/.AgentActivity'" | tail -1

echo ""
echo "=== Deploy complete ==="
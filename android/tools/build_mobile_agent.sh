#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
./gradlew :app:assembleDebug
printf '%s\n' "$ROOT/app/build/outputs/apk/debug/app-debug.apk"

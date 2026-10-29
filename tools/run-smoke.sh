#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
lumen_adb="$(command -v adb || true)"
if [[ -z "$lumen_adb" ]]; then lumen_adb="${ANDROID_HOME:-/workspace/android-sdk}/platform-tools/adb"; fi
lumen_device=()
if [[ -n "${ANDROID_SERIAL:-}" ]]; then lumen_device=(-s "$ANDROID_SERIAL"); fi
"$lumen_adb" "${lumen_device[@]}" reverse tcp:8765 tcp:8765
./scripts/build.sh :app:assembleDebug :app:assembleDebugAndroidTest
"$lumen_adb" "${lumen_device[@]}" install -r app/build/outputs/apk/debug/app-debug.apk
"$lumen_adb" "${lumen_device[@]}" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mkdir -p build
"$lumen_adb" "${lumen_device[@]}" shell am instrument -w -r com.lumenkot.app.test/androidx.test.runner.AndroidJUnitRunner | tee build/smoke-test.log
# adb may return zero even when AndroidJUnitRunner reports failures.
python3 - <<'PY'
import pathlib,re
s=pathlib.Path('build/smoke-test.log').read_text()
if not re.search(r'OK \(5 tests\)',s) or 'FAILURES!!!' in s or 'INSTRUMENTATION_FAILED' in s:
    raise SystemExit('Android smoke tests failed or did not finish')
PY

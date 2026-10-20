#!/usr/bin/env bash
# Keep status and collection in one shell: emulator-runner executes script lines separately.
set -u
mkdir -p app/build/emulator-diagnostics
adb logcat -c || true
set +e
gradle --no-daemon :app:connectedDebugAndroidTest
test_result=$?
adb pull /sdcard/Android/data/com.massisolutions.tridrone/files/smoke-screenshots app/build/emulator-screenshots || true
adb logcat -d -v threadtime > app/build/emulator-diagnostics/logcat.txt || true
if [ "$test_result" -ne 0 ]; then
  python3 - <<'PY'
from pathlib import Path
for report in sorted(Path('app/build/outputs/androidTest-results/connected').rglob('TEST-*.xml'))[:5]:
    print(f'Instrumentation report: {report}')
    print(report.read_text(errors='replace')[:30000])
PY
  rg 'AndroidRuntime|TestRunner|SMOKE_STEP|RootView|espresso|FATAL EXCEPTION' app/build/emulator-diagnostics/logcat.txt | tail -n 300 || true
fi
exit "$test_result"

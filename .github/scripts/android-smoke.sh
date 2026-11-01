#!/usr/bin/env bash
# Keep status and collection in one shell: emulator-runner executes script lines separately.
set -u
mkdir -p app/build/emulator-diagnostics
adb logcat -c || true
set +e
gradle --no-daemon :app:connectedDebugAndroidTest
test_result=$?
if ! adb pull /sdcard/Download/TriDroneSmoke app/build/emulator-screenshots; then
  echo "::error::Emulator screenshots were not retained."
  if [ "$test_result" -eq 0 ]; then test_result=1; fi
fi
if [ "$test_result" -eq 0 ]; then
  python3 - <<'PY'
import base64
from pathlib import Path
folder = Path('app/build/emulator-screenshots')
for name in ('equipment-disconnected', 'survey-disconnected', 'survey-demo-explicit', 'equipment-settings-restored', 'live-map-disconnected', 'saved-map-fit', 'sounding-float-details'):
    if not (folder / f'{name}.png').is_file():
        raise SystemExit(f'Missing emulator screenshot: {name}')
preview = base64.b64encode((folder / 'survey-disconnected-preview.png').read_bytes()).decode('ascii')
if len(preview) > 300000:
    raise SystemExit('Survey preview exceeds bounded log evidence size')
print('TRIDRONE_SURVEY_PNG_BASE64_BEGIN')
for start in range(0, len(preview), 120):
    print(preview[start:start+120])
print('TRIDRONE_SURVEY_PNG_BASE64_END')
PY
  evidence_result=$?
  if [ "$evidence_result" -ne 0 ]; then test_result=$evidence_result; fi
fi
adb logcat -d -v threadtime > app/build/emulator-diagnostics/logcat.txt || true
# The instrumentation package may be removed before this shell resumes; retain benchmark output from logcat.
python3 - <<'PYBENCH'
from pathlib import Path
log = Path('app/build/emulator-diagnostics/logcat.txt').read_text(errors='replace')
lines = [line for line in log.splitlines() if 'MAP_BENCHMARK' in line]
Path('app/build/emulator-diagnostics/map-benchmark.txt').write_text('\n'.join(lines) + '\n')
if not all(any(f'soundings={count} ' in line for line in lines) for count in (100, 1000, 10000)):
    raise SystemExit('Missing map benchmark measurements for 100/1000/10000 soundings')
PYBENCH
benchmark_result=$?
if [ "$test_result" -eq 0 ] && [ "$benchmark_result" -ne 0 ]; then test_result=$benchmark_result; fi
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

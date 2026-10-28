#!/usr/bin/env bash
# تجميع النواة بـ kotlinc وتشغيلها على فيديو حقيقي. الاستخدام: run.sh video.mp4 [kotlinc]
set -euo pipefail
# حوّل المسارات النسبية إلى مطلقة *قبل* cd (وإلا يُفسَّر testdata/meter.mp4 نسبةً إلى verify/)
VIDEO=${1:-$(dirname "$0")/../testdata/meter.mp4}; KC=${2:-kotlinc}
VIDEO=$(realpath "$VIDEO")
[[ "$KC" == */* ]] && KC=$(realpath "$KC")
[ -f "$VIDEO" ] || { echo "الفيديو غير موجود: $VIDEO" >&2; exit 2; }
cd "$(dirname "$0")"
CORE=../app/src/main/java/com/smartwater/monitor/core
mkdir -p build d0 d3
$KC $CORE/*.kt Verify.kt SelfTest.kt -include-runtime -d build/verify.jar 2>&1 | grep -v '^warning' || true
python3 dump_frames.py "$VIDEO" d0 0
python3 dump_frames.py "$VIDEO" d3 4
echo "=== بدون اهتزاز ==="; java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp build/verify.jar VerifyKt d0 clean
echo "=== مع اهتزاز صناعي ±4 بكسل ==="; java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp build/verify.jar VerifyKt d3 jitter
echo "=== اختبارات محرك الزفات ==="; java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp build/verify.jar SelfTestKt
echo "=== تحقق آلي من نتائج الفيديو (يفشل البناء إن انحرفت) ==="
python3 - <<'PY'
import csv, sys
bad = 0
for d, tag in (("d0", "clean"), ("d3", "jitter")):
    last = list(csv.DictReader(open(f"{d}/trace_{tag}.csv")))[-1]
    L, R = float(last["L_cum"]), float(last["R_cum"])
    ok = 575 <= L <= 595 and R < 3
    print(f"{tag}: L={L:.1f}° (المتوقع 584.6±10)  R={R:.1f}° (المتوقع 0)  ->", "نجح" if ok else "فشل")
    bad += (not ok)
sys.exit(1 if bad else 0)
PY

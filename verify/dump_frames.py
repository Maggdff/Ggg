#!/usr/bin/env python3
"""يقصّ نافذة ROI لكل عدّاد من كل إطار فيديو (كما يفعل التطبيق عبر OpenCV) ويكتبها ثنائيًا.
الاستخدام: dump_frames.py video.mp4 outdir [jitter_px] [fps_step]"""
import sys, struct, random, cv2, numpy as np
video, out = sys.argv[1], sys.argv[2]
jit = int(sys.argv[3]) if len(sys.argv) > 3 else 0
step = int(sys.argv[4]) if len(sys.argv) > 4 else 1
METERS = [("L", 505, 1232, 60), ("R", 1887, 1337, 62)]   # المعايرة اليدوية (cx, cy, R)
HALF = 100
random.seed(7)
cap = cv2.VideoCapture(video)
fps = cap.get(cv2.CAP_PROP_FPS) or 20.0
files = {m[0]: open(f"{out}/frames_{m[0]}.bin", "wb") for m in METERS}
# مرجع المعايرة: ROI من الإطار الأول بدون اهتزاز (مثل شاشة المعايرة في التطبيق)
cap0 = cv2.VideoCapture(video); ok0, f0 = cap0.read()
if not ok0: sys.exit(f"تعذّر فتح الفيديو: {video}")
g0 = cv2.cvtColor(f0, cv2.COLOR_BGR2GRAY)
for name, cx, cy, R in METERS:
    ox, oy = cx - HALF, cy - HALF
    roi0 = cv2.GaussianBlur(g0[oy:oy + 2 * HALF, ox:ox + 2 * HALF], (0, 0), 1.2)
    open(f"{out}/ref_{name}.bin", "wb").write(struct.pack("<iiii", 2 * HALF, 2 * HALF, ox, oy) + roi0.tobytes())
times = open(f"{out}/times.txt", "w")
i = 0; n = 0
while True:
    ok, f = cap.read()
    if not ok: break
    if i % step == 0:
        g = cv2.cvtColor(f, cv2.COLOR_BGR2GRAY)
        jx = random.uniform(-jit, jit) if jit else 0.0     # إزاحة كسرية عشوائية كما في الاهتزاز الحقيقي
        jy = random.uniform(-jit, jit) if jit else 0.0
        M = np.float32([[1, 0, -jx], [0, 1, -jy]])           # المحتوى يزاح بـ (-jx,-jy)
        gj = cv2.warpAffine(g, M, (g.shape[1], g.shape[0]), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE) if jit else g
        for name, cx, cy, R in METERS:
            ox, oy = cx - HALF, cy - HALF
            roi = gj[oy: oy + 2 * HALF, ox: ox + 2 * HALF]
            roi = cv2.GaussianBlur(roi, (0, 0), 1.2)
            files[name].write(struct.pack("<iiii", 2 * HALF, 2 * HALF, ox, oy) + roi.tobytes())
        times.write(f"{i / fps:.4f} {jx:.3f} {jy:.3f}\n"); n += 1
    i += 1
print("frames written:", n, "jitter:", jit)

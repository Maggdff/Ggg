// GET  /api/questions  -> public, returns the published question list ([] if none)
// POST /api/questions  -> admin only. body: {password, action: "login" | "save" | "reset", questions?}
const crypto = require("crypto");
const URL_ = process.env.KV_REST_API_URL || process.env.UPSTASH_REDIS_REST_URL;
const TOK = process.env.KV_REST_API_TOKEN || process.env.UPSTASH_REDIS_REST_TOKEN;
const KEY = "quiz:questions";

async function redis(cmd) {
  const r = await fetch(URL_, {
    method: "POST",
    headers: { Authorization: "Bearer " + TOK, "Content-Type": "application/json" },
    body: JSON.stringify(cmd),
  });
  if (!r.ok) throw new Error("redis " + r.status);
  return (await r.json()).result;
}
const h = (s) => crypto.createHash("sha256").update(String(s)).digest();
const same = (a, b) => crypto.timingSafeEqual(h(a), h(b));

function clean(l) {
  if (!Array.isArray(l) || l.length < 1 || l.length > 1000) return null;
  const out = [];
  for (const q of l) {
    if (!q || typeof q.t !== "string" || typeof q.c !== "string" || typeof q.e !== "string") return null;
    if (!Array.isArray(q.o) || q.o.length < 2 || q.o.length > 4 || !q.o.every((x) => typeof x === "string")) return null;
    if (!Number.isInteger(q.k) || q.k < 0 || q.k >= q.o.length) return null;
    out.push({ t: q.t.slice(0, 300), c: q.c.slice(0, 3000), o: q.o.map((x) => x.slice(0, 1000)), k: q.k, e: q.e.slice(0, 2000) });
  }
  return out;
}

module.exports = async (req, res) => {
  res.setHeader("Cache-Control", "no-store");
  try {
    if (req.method === "GET") {
      if (!URL_ || !TOK) return res.status(200).json([]);
      const v = await redis(["GET", KEY]);
      return res.status(200).json(v ? JSON.parse(v) : []);
    }
    if (req.method !== "POST") return res.status(405).json({ error: "method" });
    const pw = process.env.ADMIN_PASSWORD;
    if (!pw) return res.status(500).json({ error: "ADMIN_PASSWORD غير مضبوط في Vercel" });
    const b = typeof req.body === "string" ? JSON.parse(req.body || "{}") : req.body || {};
    if (!same(b.password || "", pw)) {
      await new Promise((r) => setTimeout(r, 800));
      return res.status(401).json({ error: "كلمة المرور خاطئة" });
    }
    if (b.action === "login") return res.status(200).json({ ok: true });
    if (!URL_ || !TOK) return res.status(500).json({ error: "قاعدة البيانات (Upstash Redis) غير مربوطة بالمشروع" });
    if (b.action === "save") {
      const q = clean(b.questions);
      if (!q) return res.status(400).json({ error: "بيانات الأسئلة غير صالحة" });
      await redis(["SET", KEY, JSON.stringify(q)]);
      return res.status(200).json({ ok: true, count: q.length });
    }
    if (b.action === "reset") {
      await redis(["DEL", KEY]);
      return res.status(200).json({ ok: true });
    }
    return res.status(400).json({ error: "action" });
  } catch (e) {
    return res.status(500).json({ error: "خطأ في الخادم" });
  }
};

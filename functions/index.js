// بروكسي بيانات إنستغرام — التوكن هون بس (server-side)، ما بيوصل للمتصفح أبداً
const { onRequest } = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");

const API_VERSION = "v27.0";
const FIELDS =
  "id,username,name,profile_picture_url,biography,followers_count,follows_count,media_count";

// كاش بسيط بالذاكرة عشان ما نضرب Graph API مع كل زائر
const CACHE_MS = 20 * 1000;
let cache = { at: 0, body: null };

exports.profile = onRequest(
  { cors: true, region: "us-central1", timeoutSeconds: 20 },
  async (req, res) => {
    if (req.method !== "GET" && req.method !== "HEAD") {
      res.status(405).json({ error: "method_not_allowed" });
      return;
    }

    const token = process.env.IG_ACCESS_TOKEN;
    const userId = process.env.IG_USER_ID;

    if (!token || !userId) {
      logger.error("IG_ACCESS_TOKEN / IG_USER_ID غير مضبوطين بالبيئة");
      res.status(500).json({ error: "server_not_configured" });
      return;
    }

    const now = Date.now();
    if (cache.body && now - cache.at < CACHE_MS) {
      res.set("Cache-Control", "public, max-age=10, s-maxage=20");
      res.status(200).json(cache.body);
      return;
    }

    const url =
      `https://graph.facebook.com/${API_VERSION}/${encodeURIComponent(userId)}` +
      `?fields=${encodeURIComponent(FIELDS)}` +
      `&access_token=${encodeURIComponent(token)}`;

    try {
      const upstream = await fetch(url, { signal: AbortSignal.timeout(9000) });
      const json = await upstream.json().catch(() => null);

      if (!upstream.ok || !json || json.error) {
        // ما منرجّع رسالة Meta الحقيقية (ممكن تحتوي تفاصيل) — بس كود مختصر
        logger.error("Graph API error", {
          status: upstream.status,
          code: json && json.error ? json.error.code : null,
          type: json && json.error ? json.error.type : null,
        });
        res.status(502).json({ error: "upstream_error" });
        return;
      }

      const body = {
        username: json.username || "",
        bio: json.biography || "",
        posts: json.media_count ?? null,
        followers: json.followers_count ?? null,
        following: json.follows_count ?? null,
        avatar: json.profile_picture_url || "",
      };

      cache = { at: now, body };
      res.set("Cache-Control", "public, max-age=10, s-maxage=20");
      res.status(200).json(body);
    } catch (err) {
      logger.error("فشل الوصول لـ Graph API", { message: err && err.message });
      res.status(502).json({ error: "upstream_unreachable" });
    }
  }
);

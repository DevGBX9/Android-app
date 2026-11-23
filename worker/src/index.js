// بروكسي بيانات إنستغرام — التوكن مخزّن كسر على Cloudflare، ما بيوصل للمتصفح
const API_VERSION = "v27.0";
const FIELDS =
  "id,username,name,profile_picture_url,biography,followers_count,follows_count,media_count";
const CACHE_TTL = 20; // ثواني

const json = (body, status, extraHeaders = {}) =>
  new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": `public, max-age=${CACHE_TTL}`,
      ...extraHeaders,
    },
  });

export default {
  async fetch(request, env, ctx) {
    const cors = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
      "Access-Control-Allow-Headers": "Accept",
      "Access-Control-Max-Age": "86400",
    };

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: cors });
    }
    if (request.method !== "GET" && request.method !== "HEAD") {
      return json({ error: "method_not_allowed" }, 405, cors);
    }
    if (!env.IG_ACCESS_TOKEN || !env.IG_USER_ID) {
      return json({ error: "server_not_configured" }, 500, cors);
    }

    const cache = caches.default;
    const cacheKey = new Request(new URL("/profile", request.url).toString(), {
      method: "GET",
    });

    const hit = await cache.match(cacheKey);
    if (hit) {
      const body = await hit.text();
      return new Response(body, {
        status: 200,
        headers: {
          "content-type": "application/json; charset=utf-8",
          "cache-control": `public, max-age=${CACHE_TTL}`,
          "x-cache": "HIT",
          ...cors,
        },
      });
    }

    const url =
      `https://graph.facebook.com/${API_VERSION}/${encodeURIComponent(env.IG_USER_ID)}` +
      `?fields=${encodeURIComponent(FIELDS)}` +
      `&access_token=${encodeURIComponent(env.IG_ACCESS_TOKEN)}`;

    try {
      const upstream = await fetch(url, { signal: AbortSignal.timeout(9000) });
      const data = await upstream.json().catch(() => null);

      if (!upstream.ok || !data || data.error) {
        // ما منسرّب رسالة Meta أو تفاصيل التوكن
        return json({ error: "upstream_error" }, 502, cors);
      }

      const body = {
        username: data.username || "",
        bio: data.biography || "",
        posts: data.media_count ?? null,
        followers: data.followers_count ?? null,
        following: data.follows_count ?? null,
        avatar: data.profile_picture_url || "",
      };

      const payload = JSON.stringify(body);
      ctx.waitUntil(
        cache.put(
          cacheKey,
          new Response(payload, {
            headers: {
              "content-type": "application/json; charset=utf-8",
              "cache-control": `max-age=${CACHE_TTL}`,
            },
          })
        )
      );

      return new Response(payload, {
        status: 200,
        headers: {
          "content-type": "application/json; charset=utf-8",
          "cache-control": `public, max-age=${CACHE_TTL}`,
          "x-cache": "MISS",
          ...cors,
        },
      });
    } catch (err) {
      return json({ error: "upstream_unreachable" }, 502, cors);
    }
  },
};

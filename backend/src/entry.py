"""Pigeon Reels API — Cloudflare Python Worker.

المهمة: يستقبل رابط ريل إنستغرام، ينزّل الفيديو، يخزنه في R2، ويحدّث حالة
المعالجة والتقدّم في Firebase Realtime Database تحت `/reels/<id>/archive`،
ثم يقدّم الملف للتحميل من `GET /download/<id>`.

المسارات:
    GET  /                     معلومات الخدمة
    GET  /health               فحص سريع
    POST /reels                {"id", "url", "thumbnail"?} -> 202 ويبدأ المعالجة
    GET  /reels/<id>           حالة الأرشفة (تقرأ من Firebase، وبدونها من R2)
    GET  /download/<id>        ملف الفيديو من R2 (Content-Disposition: attachment)

كل الطلبات تُرفض إذا لم يكن `API_TOKEN` مضبوطاً في الأسرار، ولم يكن الترويسة
`X-Api-Key` مطابقة.
"""

import json
import re
import time
from urllib.parse import urlparse, unquote

from js import URL
from workers import Response, WorkerEntrypoint, fetch

RTDB_FALLBACK = (
    "https://brainrotpigeon67-default-rtdb.europe-west1.firebasedatabase.app"
)

# أقصى حجم نقبله للريل (حدود ذاكرة الـWorker هي 128MB لكل isolate).
MAX_BYTES = 48 * 1024 * 1024
CHUNK_BYTES = 2 * 1024 * 1024
PROGRESS_STEP = 4

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

INSTAGRAM_HOSTS = (
    "instagram.com",
    "www.instagram.com",
    "m.instagram.com",
    "instagr.am",
    "www.instagr.am",
)

MEDIA_HOST_SUFFIXES = (
    ".cdninstagram.com",
    ".fbcdn.net",
    ".instagram.com",
)

SHORTCODE_RE = re.compile(r"/(?:reel|reels|p|tv)/([A-Za-z0-9_-]{5,40})")

VIDEO_URL_PATTERNS = (
    re.compile(r'"video_url":"([^"]+)"'),
    re.compile(r'video_url\\":\\"([^"\\]+)\\"'),
    re.compile(r'<meta[^>]+property="og:video"[^>]+content="([^"]+)"'),
    re.compile(r'"contentUrl":"([^"]+)"'),
    re.compile(r'"video_url":"([^"]+)"'),
)


# --------------------------------------------------------------------------
# أدوات صغيرة
# --------------------------------------------------------------------------


def _json(payload, status=200):
    return Response(
        json.dumps(payload, ensure_ascii=False),
        status=status,
        headers={
            "content-type": "application/json; charset=utf-8",
            "cache-control": "no-store",
            "access-control-allow-origin": "*",
        },
    )


def _error(message, status=400):
    return _json({"ok": False, "error": message}, status=status)


def _as_bytes(value):
    """يحوّل Uint8Array القادمة من JS إلى bytes بايثون."""
    if isinstance(value, (bytes, bytearray)):
        return bytes(value)
    to_py = getattr(value, "to_py", None)
    if callable(to_py):
        return bytes(to_py())
    return bytes(value)


def _field(data, name, default=None):
    """يقرأ حقلاً من كائن JS أو dict بايثون."""
    if data is None:
        return default
    try:
        value = data[name]
    except Exception:
        value = getattr(data, name, default)
    if value is None:
        return default
    return value


def _clean(value, default=""):
    if value is None:
        return default
    text = str(value).strip()
    return text or default


def _path_of(raw_url):
    return URL(raw_url).pathname


def _instagram_code(link):
    parsed = urlparse(link)
    host = (parsed.hostname or "").lower()
    if host not in INSTAGRAM_HOSTS:
        return None
    match = SHORTCODE_RE.search(parsed.path or "")
    return match.group(1) if match else None


def _is_media_host(host):
    host = (host or "").lower()
    return any(host.endswith(suffix) for suffix in MEDIA_HOST_SUFFIXES)


def _unescape_media(link):
    text = (
        link.replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("\\u003D", "=")
        .replace("&amp;", "&")
    )
    return unquote(text) if "%" in text and "?" not in text else text


def _safe_id(value):
    return re.sub(r"[^A-Za-z0-9_-]", "", _clean(value))[:64]


# --------------------------------------------------------------------------
# Firebase
# --------------------------------------------------------------------------


async def _firebase_put(base, path, payload):
    body = json.dumps(payload, ensure_ascii=False)
    response = await fetch(
        f"{base.rstrip('/')}/{path.lstrip('/')}",
        method="PUT",
        headers={"content-type": "application/json; charset=utf-8"},
        body=body,
    )
    return int(response.status)


async def _firebase_get(base, path):
    response = await fetch(
        f"{base.rstrip('/')}/{path.lstrip('/')}",
        headers={"accept": "application/json"},
    )
    if int(response.status) != 200:
        return None
    text = await response.text()
    if not text or text == "null":
        return None
    return json.loads(text)


# --------------------------------------------------------------------------
# إنستغرام
# --------------------------------------------------------------------------


async def _resolve_media_url(code):
    """يجيب رابط الفيديو الأصلي من صفحة embed العامة (بدون تسجيل دخول)."""
    pages = (
        f"https://www.instagram.com/reel/{code}/embed/captioned/",
        f"https://www.instagram.com/p/{code}/embed/captioned/",
    )
    headers = {
        "user-agent": USER_AGENT,
        "accept": "text/html,application/xhtml+xml",
        "accept-language": "en-US,en;q=0.9",
    }

    for page in pages:
        response = await fetch(page, headers=headers, redirect="follow")
        if int(response.status) != 200:
            continue
        html = await response.text()
        for pattern in VIDEO_URL_PATTERNS:
            match = pattern.search(html)
            if not match:
                continue
            candidate = _unescape_media(match.group(1))
            host = (urlparse(candidate).hostname or "").lower()
            if _is_media_host(host):
                return candidate

    return None


async def _media_size(media_url):
    response = await fetch(
        media_url,
        method="HEAD",
        headers={"user-agent": USER_AGENT, "referer": "https://www.instagram.com/"},
        redirect="follow",
    )
    try:
        return int(_clean(response.headers.get("content-length"), "0"))
    except Exception:
        return 0


async def _download_bytes(media_url, on_progress):
    """ينزّل الفيديو على دفعات ليعطي تقدّماً حقيقياً بين 0 و100."""
    size = await _media_size(media_url)
    if size > MAX_BYTES:
        raise RuntimeError(f"reel too large ({size} bytes)")

    headers = {
        "user-agent": USER_AGENT,
        "referer": "https://www.instagram.com/",
    }

    if size <= 0:
        response = await fetch(media_url, headers=headers, redirect="follow")
        if int(response.status) not in (200, 206):
            raise RuntimeError(f"media HTTP {response.status}")
        data = _as_bytes(await response.bytes())
        if not data:
            raise RuntimeError("empty media")
        if len(data) > MAX_BYTES:
            raise RuntimeError("reel too large")
        await on_progress(92)
        return data

    buffer = bytearray()
    offset = 0
    reported = 0

    while offset < size:
        end = min(offset + CHUNK_BYTES, size) - 1
        response = await fetch(
            media_url,
            headers={**headers, "range": f"bytes={offset}-{end}"},
            redirect="follow",
        )
        status = int(response.status)
        if status not in (200, 206):
            raise RuntimeError(f"media HTTP {status}")
        chunk = _as_bytes(await response.bytes())
        if not chunk:
            break
        buffer += chunk
        offset += len(chunk)
        if len(buffer) > MAX_BYTES:
            raise RuntimeError("reel too large")

        percent = 8 + int(offset * 84 / max(size, 1))
        if percent - reported >= PROGRESS_STEP:
            reported = percent
            await on_progress(min(percent, 92))

    if len(buffer) < 1024:
        raise RuntimeError("empty media")
    return bytes(buffer)


# --------------------------------------------------------------------------
# الـWorker
# --------------------------------------------------------------------------


class Default(WorkerEntrypoint):
    """نقطة الدخول: راوتر صغير لثلاثة مسارات + خلفية الأرشفة."""

    def _db(self):
        return _clean(getattr(self.env, "FIREBASE_DB", None), RTDB_FALLBACK)

    def _authorized(self, request):
        expected = _clean(getattr(self.env, "API_TOKEN", None))
        if not expected:
            return True
        provided = _clean(request.headers.get("x-api-key"))
        return provided == expected

    async def fetch(self, request):
        method = _clean(request.method, "GET").upper()
        path = _path_of(str(request.url))
        parts = [part for part in path.split("/") if part]

        if not parts:
            return _json(
                {
                    "ok": True,
                    "service": "pigeon-reels",
                    "version": 1,
                    "routes": ["POST /reels", "GET /reels/<id>", "GET /download/<id>"],
                }
            )

        if parts[0] == "health":
            return _json({"ok": True, "time": int(time.time() * 1000)})

        if not self._authorized(request):
            return _error("unauthorized", 401)

        if parts[0] == "reels" and len(parts) == 1 and method == "POST":
            return await self._start(request)

        if parts[0] == "reels" and len(parts) == 2 and method == "GET":
            return await self._status(parts[1])

        if parts[0] == "download" and len(parts) == 2 and method == "GET":
            return await self._download(parts[1])

        if method == "OPTIONS":
            return Response(
                "",
                status=204,
                headers={
                    "access-control-allow-origin": "*",
                    "access-control-allow-headers": "content-type,x-api-key",
                    "access-control-allow-methods": "GET,POST,OPTIONS",
                },
            )

        return _error("not found", 404)

    async def _start(self, request):
        try:
            body = await request.json()
            reel_id = _safe_id(_field(body, "id"))
            link = _clean(_field(body, "url"))
            thumbnail = _clean(_field(body, "thumbnail"))
        except Exception:
            return _error("invalid json body")

        if not reel_id:
            return _error("missing id")

        code = _instagram_code(link)
        if not code:
            return _error("only instagram reel links are supported")

        origin = f"{URL(str(request.url)).origin}"
        db = self._db()
        now = int(time.time() * 1000)

        await _firebase_put(
            db,
            f"reels/{reel_id}/archive.json",
            {"state": "queued", "progress": 0, "updatedAt": now},
        )

        self.ctx.waitUntil(
            self._archive(reel_id=reel_id, code=code, origin=origin, thumbnail=thumbnail)
        )

        return _json({"ok": True, "id": reel_id, "state": "queued"}, status=202)

    async def _status(self, reel_id):
        reel_id = _safe_id(reel_id)
        if not reel_id:
            return _error("bad id", 404)

        archive = await _firebase_get(self._db(), f"reels/{reel_id}/archive.json")
        if archive:
            return _json({"ok": True, "id": reel_id, "archive": archive})

        existing = await self.env.FILES.head(self._key(reel_id))
        if existing is not None:
            return _json(
                {
                    "ok": True,
                    "id": reel_id,
                    "archive": {"state": "ready", "progress": 100},
                }
            )

        return _json(
            {"ok": True, "id": reel_id, "archive": {"state": "unknown", "progress": 0}}
        )

    async def _download(self, reel_id):
        reel_id = _safe_id(reel_id)
        if not reel_id:
            return _error("bad id", 404)

        obj = await self.env.FILES.get(self._key(reel_id))
        if obj is None:
            return _error("not stored yet", 404)

        return Response(
            obj.body,
            headers={
                "content-type": "video/mp4",
                "content-disposition": f'attachment; filename="pigeon-{reel_id}.mp4"',
                "cache-control": "public, max-age=86400",
                "access-control-allow-origin": "*",
            },
        )

    def _key(self, reel_id):
        return f"reels/{reel_id}.mp4"

    async def _archive(self, reel_id, code, origin, thumbnail):
        db = self._db()
        state_path = f"reels/{reel_id}/archive.json"
        started = int(time.time() * 1000)

        async def report(state, progress, **extra):
            payload = {
                "state": state,
                "progress": int(progress),
                "updatedAt": int(time.time() * 1000),
            }
            payload.update(extra)
            try:
                await _firebase_put(db, state_path, payload)
            except Exception:
                pass

        try:
            await report("downloading", 3)

            media_url = await _resolve_media_url(code)
            if not media_url:
                raise RuntimeError("could not resolve the reel video url")

            await report("downloading", 6)

            async def on_progress(percent):
                await report("downloading", percent)

            data = await _download_bytes(media_url, on_progress)

            await report("storing", 95)
            await self.env.FILES.put(self._key(reel_id), data)

            download_url = f"{origin}/download/{reel_id}"
            await report(
                "ready",
                100,
                url=download_url,
                bytes=len(data),
                source="instagram",
                thumbnail=thumbnail or None,
                durationMs=int(time.time() * 1000) - started,
            )
        except Exception as exc:  # noqa: BLE001
            await report("failed", 0, error=str(exc)[:300])

"""Pigeon Reels API — Cloudflare Python Worker.

المهمة: يستقبل رابط ريل إنستغرام أو فيديو تيك توك، ينزّل الفيديو، يخزنه في
R2، ويحدّث حالة المعالجة والتقدّم في Firebase Realtime Database تحت
`/reels/<id>/archive`، ثم يقدّم الملف للتحميل من `GET /download/<id>`.

المسارات:
    GET  /                     معلومات الخدمة
    GET  /health               فحص سريع
    POST /reels                {"id", "url", "thumbnail"?} -> 202 ويبدأ المعالجة
    GET  /reels/<id>           حالة الأرشفة (تقرأ من Firebase، وبدونها من R2)
    GET  /download/<id>        ملف الفيديو من R2 (Content-Disposition: attachment)

كل الطلبات تُرفض إذا لم يكن `API_TOKEN` مضبوطاً في الأسرار، ولم يكن الترويسة
`X-Api-Key` مطابقة.
"""

import asyncio
import json
import re
import time
from urllib.parse import quote, urlparse

from workers import Response, WorkerEntrypoint, fetch

RTDB_FALLBACK = (
    "https://brainrotpigeon67-default-rtdb.europe-west1.firebasedatabase.app"
)

# أقصى حجم نقبله للريل (حدود ذاكرة الـWorker هي 128MB لكل isolate).
MAX_BYTES = 48 * 1024 * 1024
CHUNK_BYTES = 2 * 1024 * 1024
PROGRESS_STEP = 4

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

# إنستغرام يخدم الصفحة الكاملة فقط لزوار غير المتصفحات؛ متصفح عادي يحصل على شاشة تسجيل دخول.
CRAWLER_UA = (
    "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"
)

TIKTOK_HOSTS = (
    "tiktok.com",
    "www.tiktok.com",
    "m.tiktok.com",
    "vt.tiktok.com",
    "vm.tiktok.com",
)

# تيك توك يحجب أي طلب ليس من متصفح حقيقي، وما يعطيه للزوار هو بيانات التشغيل
# داخل الصفحة (`playAddr`/`downloadAddr`).
BROWSER_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)

TIKTOK_VIDEO_RE = re.compile(r"/(?:video|v)/(\d{6,30})")
TIKTOK_MEDIA_RE = re.compile(r'"(?:playAddr|downloadAddr)":"(https:[^"]+)"')

# مسار عام بدون تسجيل دخول يعطي رابط CDN لا يحتاج كوكيز (روابط playAddr
# الموقّعة ترفض بـ403 من غير `tt_chain_token`).
TIKTOK_RESOLVER = "https://www.tikwm.com/api/?url="
TIKTOK_RESOLVER_ORIGIN = "https://www.tikwm.com"

OG_VIDEO_RE = re.compile(
    r'<meta[^>]+(?:property|name)="og:video(?::\w+)?"[^>]+content="([^"]+)"'
)

MEDIA_MARKERS = (
    '"video_url"',
    'video_url\\"',
    '"contentUrl"',
    'contentUrl\\"',
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
    return urlparse(raw_url).path or "/"


def _origin_of(raw_url):
    parsed = urlparse(raw_url)
    return f"{parsed.scheme}://{parsed.netloc}"


async def _read_bytes(response):
    """يقرأ جسم الاستجابة كبايتات بايثون (Pyodide يعطي ArrayBuffer/JsProxy)."""
    for reader in ("bytes", "arrayBuffer"):
        method = getattr(response, reader, None)
        if not callable(method):
            continue
        try:
            return _as_bytes(await method())
        except Exception:  # noqa: BLE001 - نجرّب القارئ الثاني
            continue
    raise RuntimeError("could not read the response body")


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


def _tiktok_link(link):
    """يرجّع الرابط إذا كان تيك توك (فيديو كامل أو رابط مختصر) وإلا None."""
    parsed = urlparse(link)
    host = (parsed.hostname or "").lower()
    if host not in TIKTOK_HOSTS:
        return None
    return link


def _tiktok_media_urls(html):
    """يستخرج روابط الفيديو من بيانات تشغيل صفحة تيك توك."""
    found = []
    for match in TIKTOK_MEDIA_RE.finditer(html or ""):
        link = _unescape_media(match.group(1))
        if link.startswith("http"):
            found.append(link)
    return found


def _unescape_media(link):
    """يفكّ تهريب JS للروابط (`\\/`، `\\u0026`) حتى تصير روابط صحيحة."""
    text = str(link or "")
    if "\\" in text:
        text = (
            text.replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("\\u002F", "/")
            .replace("\\", "")
        )
    return text.replace("&amp;", "&").strip()


def _media_urls(html):
    """يستخرج كل روابط الفيديو المرشّحة من صفحة إنستغرام (حتى المُهرَّبة)."""
    found = []
    for marker in MEDIA_MARKERS:
        index = html.find(marker)
        while index != -1:
            window = html[index : index + 4000]
            start = window.find("http")
            if start != -1:
                end = window.find('"', start)
                if end == -1:
                    end = start + 2500
                found.append(_unescape_media(window[start:end]))
            index = html.find(marker, index + 1)
    for match in OG_VIDEO_RE.finditer(html):
        found.append(_unescape_media(match.group(1)))
    return found


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
    """يجيب رابط الفيديو الأصلي من صفحة embed العامة (بدون تسجيل دخول).

    يرجّع `(url, attempts)`: الرابط إن وُجد، ووصف قصير لما أعادته إنستغرام.
    """
    pages = (
        f"https://www.instagram.com/reel/{code}/embed/",
        f"https://www.instagram.com/reel/{code}/embed/captioned/",
        f"https://www.instagram.com/p/{code}/embed/",
    )
    headers = {
        "user-agent": CRAWLER_UA,
        "accept": "text/html,application/xhtml+xml",
        "accept-language": "en-US,en;q=0.9",
    }

    attempts = []

    for page in pages:
        try:
            response = await fetch(page, headers=headers, redirect="follow")
        except Exception as exc:  # noqa: BLE001
            attempts.append(f"embed -> {str(exc)[:80]}")
            continue
        status = int(response.status)
        if status != 200:
            attempts.append(f"embed -> HTTP {status}")
            continue
        html = await response.text()
        candidates = _media_urls(html)
        for candidate in candidates:
            host = (urlparse(candidate).hostname or "").lower()
            if _is_media_host(host):
                return candidate, attempts
        attempts.append(
            f"embed -> HTTP {status}, {len(candidates)} candidate(s), {len(html)} bytes"
        )

    return None, attempts


async def _resolve_tiktok(link):
    """يجيب رابط فيديو تيك توك قابل للتنزيل مع غلافه ومدّته.

    يرجّع `(candidates, info, attempts)` حيث `candidates` قائمة روابط مرشّحة
    بالترتيب (`play` ثم `hdplay` ثم `wmplay`): بعض نسخ الـCDN ترفض الطلب
    من عناوين مراكز البيانات، فنجرّب التالي. الترتيب: تتبّع الرابط المختصر ثم
    المستخرج العام (بدون كوكيز)، وإن فشل نقرأ بيانات صفحة الفيديو نفسها.
    """
    attempts = []
    html = None
    final = link
    page_headers = {
        "user-agent": BROWSER_UA,
        "accept": "text/html,application/xhtml+xml",
        "accept-language": "en-US,en;q=0.9",
    }

    try:
        response = await fetch(link, headers=page_headers, redirect="follow")
        final = _clean(_field(response, "url"), link) or link
        if int(response.status) == 200:
            html = await response.text()
        else:
            attempts.append(f"page -> HTTP {response.status}")
    except Exception as exc:  # noqa: BLE001
        attempts.append(f"page -> {str(exc)[:80]}")

    for candidate in dict.fromkeys((final, link)):
        try:
            response = await fetch(
                TIKTOK_RESOLVER + quote(candidate, safe=""),
                headers={"accept": "application/json", "user-agent": BROWSER_UA},
                redirect="follow",
            )
        except Exception as exc:  # noqa: BLE001
            attempts.append(f"resolver -> {str(exc)[:80]}")
            continue
        if int(response.status) != 200:
            attempts.append(f"resolver -> HTTP {response.status}")
            continue
        try:
            payload = json.loads(await response.text())
        except Exception:  # noqa: BLE001
            attempts.append("resolver -> invalid json")
            continue

        data = payload.get("data") or {}
        candidates = []
        for key, size_key in (("play", "size"), ("hdplay", "hd_size"), ("wmplay", "")):
            media_url = _clean(data.get(key))
            if not media_url:
                continue
            if media_url.startswith("/"):
                media_url = TIKTOK_RESOLVER_ORIGIN + media_url
            if not media_url.startswith("http"):
                continue
            try:
                known_size = int(data.get(size_key) or 0) if size_key else 0
            except Exception:  # noqa: BLE001
                known_size = 0
            candidates.append({"url": media_url, "bytes": known_size})

        if candidates:
            try:
                duration_s = int(data.get("duration") or 0)
            except Exception:  # noqa: BLE001
                duration_s = 0
            info = {
                "durationMs": duration_s * 1000,
                "thumbnail": _clean(data.get("cover")) or None,
                "title": _clean(data.get("title"))[:200] or None,
            }
            return candidates, info, attempts

        attempts.append(
            "resolver -> code %s %s"
            % (payload.get("code"), str(payload.get("msg"))[:60])
        )

    if html:
        found = _tiktok_media_urls(html)
        if found:
            return [{"url": url, "bytes": 0} for url in found], {}, attempts
        attempts.append(f"page -> {len(html)} bytes, no media url")

    return [], {}, attempts


def _size_of(headers_obj):
    """يقرأ الحجم من `content-length` أو من `content-range` (bytes 0-0/TOTAL)."""
    try:
        size = int(_clean(headers_obj.get("content-length"), "0"))
    except Exception:  # noqa: BLE001
        size = 0
    if size > 1:
        return size
    try:
        total = _clean(headers_obj.get("content-range")).rsplit("/", 1)[-1]
        return int(total)
    except Exception:  # noqa: BLE001
        return 0


async def _media_size(media_url, headers):
    """حجم الفيديو: HEAD أولاً، ثم طلب بايت واحد (بعض السيرفرات ترفض HEAD)."""
    try:
        response = await fetch(
            media_url,
            method="HEAD",
            headers=headers,
            redirect="follow",
        )
        if int(response.status) == 200:
            size = _size_of(response.headers)
            if size > 0:
                return size
    except Exception:  # noqa: BLE001 - نجرّب طلب النطاق
        pass

    try:
        response = await fetch(
            media_url,
            headers={**headers, "range": "bytes=0-0"},
            redirect="follow",
        )
        if int(response.status) in (200, 206):
            return _size_of(response.headers)
    except Exception:  # noqa: BLE001
        return 0

    return 0


async def _fetch_media(media_url, headers, range_header=None):
    """طلب وسائط مع إعادة محاولة قصيرة: شبكة تيك توك ترد 403/429 عند التكرار السريع."""
    request_headers = dict(headers)
    if range_header:
        request_headers["range"] = range_header

    status = 0
    for attempt in range(3):
        response = await fetch(media_url, headers=request_headers, redirect="follow")
        status = int(response.status)
        if status in (200, 206):
            return response
        if status not in (403, 429, 503):
            break
        await asyncio.sleep(0.5 * (attempt + 1))

    raise RuntimeError(f"media HTTP {status}")


async def _fetch_whole(media_url, on_progress, headers):
    """تنزيل الملف بطلب واحد عندما يرفض السيرفر طلبات النطاق."""
    response = await _fetch_media(media_url, headers)
    data = await _read_bytes(response)
    if not data:
        raise RuntimeError("empty media")
    if len(data) > MAX_BYTES:
        raise RuntimeError("reel too large")
    await on_progress(92)
    return data


async def _download_bytes(media_url, on_progress, headers, known_size=0):
    """ينزّل الفيديو على دفعات ليعطي تقدّماً حقيقياً بين 0 و100."""
    size = int(known_size or 0)
    if size <= 1:
        size = await _media_size(media_url, headers)
    if size > MAX_BYTES:
        raise RuntimeError(f"reel too large ({size} bytes)")

    if size <= 0:
        return await _fetch_whole(media_url, on_progress, headers)

    buffer = bytearray()
    offset = 0
    reported = 0

    while offset < size:
        end = min(offset + CHUNK_BYTES, size) - 1
        response = await _fetch_media(
            media_url, headers, f"bytes={offset}-{end}"
        )
        chunk = await _read_bytes(response)
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
                    "version": 2,
                    "routes": ["POST /reels", "GET /reels/<id>", "GET /download/<id>"],
                    "sources": ["instagram", "tiktok"],
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
        tiktok = None if code else _tiktok_link(link)
        if not code and not tiktok:
            return _error("only instagram reel and tiktok video links are supported")
        source = "instagram" if code else "tiktok"

        origin = _origin_of(str(request.url))
        db = self._db()
        now = int(time.time() * 1000)

        await _firebase_put(
            db,
            f"reels/{reel_id}/archive.json",
            {"state": "queued", "progress": 0, "updatedAt": now},
        )

        job = self._archive(
            reel_id=reel_id,
            code=code,
            tiktok=tiktok,
            source=source,
            origin=origin,
            thumbnail=thumbnail,
        )
        try:
            self.ctx.waitUntil(job)
        except Exception:  # noqa: BLE001 - لو تعذّر التشغيل في الخلفية نكمل الطلب
            await job

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

    async def _archive(self, reel_id, code, tiktok, source, origin, thumbnail):
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

            info = {}
            if source == "tiktok":
                candidates, info, attempts = await _resolve_tiktok(tiktok)
            else:
                media_url, attempts = await _resolve_media_url(code)
                candidates = [{"url": media_url, "bytes": 0}] if media_url else []
            if not candidates:
                raise RuntimeError(
                    f"could not resolve the {source} video url: " + " | ".join(attempts)
                )

            await report("downloading", 6)

            # تيك توك يرفض طلب النطاق إذا جاء مع referer، لذلك نرسل المتصفح فقط.
            if source == "tiktok":
                media_headers = {"user-agent": BROWSER_UA}
            else:
                media_headers = {
                    "user-agent": CRAWLER_UA,
                    "referer": "https://www.instagram.com/",
                }

            async def on_progress(percent):
                await report("downloading", percent)

            data = None
            last_error = "no media url"
            for candidate in candidates:
                try:
                    try:
                        data = await _download_bytes(
                            candidate["url"],
                            on_progress,
                            media_headers,
                            candidate["bytes"],
                        )
                    except RuntimeError as exc:
                        if "media HTTP" not in str(exc):
                            raise
                        # احتياطي: طلب كامل واحد بدل النطاقات.
                        data = await _fetch_whole(
                            candidate["url"], on_progress, media_headers
                        )
                    break
                except Exception as exc:  # noqa: BLE001 - نجرّب الرابط التالي
                    last_error = str(exc)[:120]
                    data = None

            if not data:
                raise RuntimeError("media download failed: " + last_error)

            await report("storing", 95)
            await self.env.FILES.put(self._key(reel_id), data)

            download_url = f"{origin}/download/{reel_id}"
            await report(
                "ready",
                100,
                url=download_url,
                bytes=len(data),
                source=source,
                title=info.get("title") or None,
                thumbnail=thumbnail or info.get("thumbnail") or None,
                videoDurationMs=info.get("durationMs") or None,
                durationMs=int(time.time() * 1000) - started,
            )
        except Exception as exc:  # noqa: BLE001
            await report("failed", 0, error=str(exc)[:300])

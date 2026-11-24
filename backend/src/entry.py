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

# روابط الوسائط التي يقبلها الـWorker من التطبيق: CDN تيك توك فقط، حتى لا يتحوّل
# الطلب إلى جالب لأي رابط داخلي (SSRF).
APP_MEDIA_HOSTS = (
    "tiktokcdn.com",
    "tiktokcdn-us.com",
    "tiktokcdn-eu.com",
    "tiktok.com",
)
CHUNK_BYTES = 2 * 1024 * 1024
PROGRESS_STEP = 4

# طلبات Cloudflare بلا مهلة افتراضية، ومهام `waitUntil` تُلغى بعد ~30 ثانية من
# انتهاء الاستجابة، فنجعل كل طلب محدوداً والمهمة كلها أقصر من ذلك: بدل أن
# تُلغى المهمة بلا أثر، نفشل بخطأ واضح يقدر التطبيق يعيد المحاولة بعده.
FETCH_TIMEOUT_S = 8
ARCHIVE_BUDGET_S = 24
STALE_JOB_MS = 75_000

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

TIKTOK_VIDEO_RE = re.compile(r"/(?:video|v|photo)/(\d{6,30})")

# كوكيز الجلسة التي تُشترط لروابط `playAddr` من الموقع (`*-prime.tiktok.com`)
# ترد 403 بدونها، فنأخذها من نفس استجابة الصفحة.
TIKTOK_COOKIES = ("tt_chain_token", "tt_csrf_token", "ttwid", "msToken")

# صفحة الموبايل تعطي نفس الفيديو من `*.tiktokcdn.com` بلا كوكيز ولا توقيع جلسة.
TIKTOK_MOBILE_PAGE = "https://m.tiktok.com/v/{id}.html"

# احتياط أخير: tikwm يحجب Cloudflare بحصة يومية مشتركة (`Free Api Limit`).
TIKTOK_RESOLVER = "https://www.tikwm.com/api/?url="
TIKTOK_RESOLVER_ORIGIN = "https://www.tikwm.com"
TIKTOK_OEMBED = "https://www.tiktok.com/oembed?url="

TIKTOK_PAGE_HEADERS = {
    "accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "accept-language": "en-US,en;q=0.9",
}

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


async def _fetch(url, **options):
    """طلب شبكة بمهلة قصوى، لأن fetch في الـWorker بلا مهلة افتراضية."""
    return await asyncio.wait_for(fetch(url, **options), FETCH_TIMEOUT_S)


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


def _tiktok_video_id(link):
    """رقم الفيديو من `/video/<id>` أو `/v/<id>.html`."""
    match = TIKTOK_VIDEO_RE.search(urlparse(link or "").path or "")
    return match.group(1) if match else None


def _tiktok_media_urls(html, key):
    """روابط الفيديو من بيانات تشغيل الصفحة: `playAddr` نظيف و`downloadAddr` بعلامة."""
    found = []
    pattern = re.compile(r'"%s":\s*"([^"]{20,2500})"' % key)
    for match in pattern.finditer(html or ""):
        link = _unescape_media(match.group(1))
        if link.startswith("http") and link not in found:
            found.append(link)
    return found


def _tiktok_cookies(headers_obj):
    """كوكيز تيك توك من `set-cookie` (الـ`Headers` تجمعها في قيمة واحدة)."""
    found = {}
    try:
        raw = _clean(headers_obj.get("set-cookie"))
    except Exception:  # noqa: BLE001 - بعض الاستجابات بلا ترويسات
        return found
    if not raw:
        return found
    for part in re.split(r",\s*(?=[A-Za-z0-9_\-]+=)", raw):
        name, _, rest = part.partition("=")
        name = name.strip()
        if name in TIKTOK_COOKIES and rest:
            value = rest.split(";", 1)[0].strip()
            if value:
                found[name] = value
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


def _safe_media_url(value):
    """رابط وسائط من التطبيق: HTTPS ومضيف CDN تيك توك فقط."""
    url = _clean(value)
    if not url.startswith("https://"):
        return None
    host = (urlparse(url).hostname or "").lower()
    if not host:
        return None
    if not any(host == suffix or host.endswith("." + suffix) for suffix in APP_MEDIA_HOSTS):
        return None
    return url


# --------------------------------------------------------------------------
# Firebase
# --------------------------------------------------------------------------


async def _firebase_put(base, path, payload):
    body = json.dumps(payload, ensure_ascii=False)
    response = await _fetch(
        f"{base.rstrip('/')}/{path.lstrip('/')}",
        method="PUT",
        headers={"content-type": "application/json; charset=utf-8"},
        body=body,
    )
    return int(response.status)


async def _firebase_get(base, path):
    response = await _fetch(
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
            response = await _fetch(page, headers=headers, redirect="follow")
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


async def _resolve_tiktok(link, deadline=None):
    """يجمع روابط فيديو تيك توك النظيفة (بلا علامة مائية) بترتيب الأفضلية.

    الأساس صفحة تيك توك نفسها: نتبع تحويل الرابط المختصر، ونأخذ معرّف الفيديو
    و`playAddr` (النسخة التي يشغّلها الموقع، وهي بلا علامة)، ونرسل معها كوكيز
    الصفحة و`referer` لأن روابط `*-prime.tiktok.com` ترد 403 بدونهما. ثم صفحة
    الموبايل لأنها تعطي رابط `*.tiktokcdn.com` بلا كوكيز. أما tikwm (الذي كان
    الأساس) فيحجب Cloudflare بحصة يومية مشتركة، فبقي احتياطاً أخيراً فقط.
    """
    attempts = []
    candidates = []
    info = {}
    cookies = {}

    def left():
        return 99.0 if deadline is None else deadline - time.monotonic()

    def add(media_url, label, headers=None, size=0, whole=False):
        media_url = _clean(media_url)
        if not media_url.startswith("http"):
            return
        if any(entry["url"] == media_url for entry in candidates):
            return
        request_headers = {"user-agent": BROWSER_UA, "accept": "*/*"}
        if headers:
            for name, value in headers.items():
                if value:
                    request_headers[name] = value
        candidates.append(
            {
                "url": media_url,
                "bytes": size,
                "headers": request_headers,
                "label": label,
                "whole": whole,
            }
        )

    page_headers = dict(TIKTOK_PAGE_HEADERS)
    page_headers["user-agent"] = BROWSER_UA

    # 1) صفحة الرابط: تتبع التحويل القصير وتعطي المعرّف والكوكيز وplayAddr.
    final = link
    html = ""
    try:
        response = await _fetch(link, headers=page_headers, redirect="follow")
        final = _clean(_field(response, "url"), link) or link
        cookies = _tiktok_cookies(response.headers)
        if int(response.status) == 200:
            html = await response.text()
        else:
            attempts.append(f"page -> HTTP {response.status}")
    except Exception as exc:  # noqa: BLE001
        attempts.append(f"page -> {str(exc)[:70]}")

    video_id = _tiktok_video_id(final) or _tiktok_video_id(link)

    site_headers = {
        "referer": "https://www.tiktok.com/",
        "origin": "https://www.tiktok.com",
    }
    if cookies:
        site_headers["cookie"] = "; ".join(
            f"{name}={value}" for name, value in cookies.items()
        )

    for media_url in _tiktok_media_urls(html, "playAddr"):
        add(media_url, "page/playAddr", site_headers)

    if not video_id:
        oembed = await _tiktok_oembed(final, attempts)
        video_id = _clean(oembed.get("embed_product_id")) or None
        if oembed:
            info = {
                "title": _clean(oembed.get("title"))[:200] or None,
                "thumbnail": _clean(oembed.get("thumbnail_url")) or None,
                "durationMs": 0,
            }

    # 2) صفحة الموبايل: نفس الفيديو من CDN لا يحتاج كوكيز ولا جلسة.
    if video_id and left() > 7:
        try:
            response = await _fetch(
                TIKTOK_MOBILE_PAGE.format(id=video_id),
                headers={**page_headers, "referer": "https://www.tiktok.com/"},
                redirect="follow",
            )
            if int(response.status) == 200:
                mobile_html = await response.text()
                found = _tiktok_media_urls(mobile_html, "playAddr")
                for media_url in found:
                    add(media_url, "mobile/playAddr")
                if not found:
                    attempts.append(
                        "mobile -> %d bytes, no media url" % len(mobile_html)
                    )
            else:
                attempts.append(f"mobile -> HTTP {response.status}")
        except Exception as exc:  # noqa: BLE001
            attempts.append(f"mobile -> {str(exc)[:70]}")

    # 3) النسخة المعلَّمة: أفضل من لا شيء لو غيّرت تيك توك الروابط النظيفة.
    for media_url in _tiktok_media_urls(html, "downloadAddr"):
        add(media_url, "page/downloadAddr", site_headers)

    # 4) tikwm: احتياط أخير فقط لأنه يحجب Cloudflare بحصة يومية مشتركة.
    payload = None
    if not candidates and left() > 5:
        for candidate_link in dict.fromkeys((final, link)):
            try:
                response = await _fetch(
                    TIKTOK_RESOLVER + quote(candidate_link, safe=""),
                    headers={"accept": "application/json", "user-agent": BROWSER_UA},
                    redirect="follow",
                )
            except Exception as exc:  # noqa: BLE001
                attempts.append(f"resolver -> {str(exc)[:70]}")
                continue
            if int(response.status) != 200:
                attempts.append(f"resolver -> HTTP {response.status}")
                continue
            try:
                parsed = json.loads(await response.text())
            except Exception:  # noqa: BLE001
                attempts.append("resolver -> invalid json")
                continue
            if parsed.get("data"):
                payload = parsed
                break
            attempts.append(
                "resolver -> code %s %s"
                % (parsed.get("code"), str(parsed.get("msg"))[:60])
            )

    data = (payload or {}).get("data") or {}
    for key, size_key in (("play", "size"), ("hdplay", "hd_size"), ("wmplay", "")):
        media_url = _clean(data.get(key))
        if not media_url:
            continue
        if media_url.startswith("/"):
            media_url = TIKTOK_RESOLVER_ORIGIN + media_url
        try:
            known_size = int(data.get(size_key) or 0) if size_key else 0
        except Exception:  # noqa: BLE001
            known_size = 0
        add(media_url, f"tikwm/{key}", size=known_size)

    if data:
        try:
            duration_s = int(data.get("duration") or 0)
        except Exception:  # noqa: BLE001
            duration_s = 0
        info = {
            "durationMs": duration_s * 1000,
            "thumbnail": info.get("thumbnail") or _clean(data.get("cover")) or None,
            "title": info.get("title") or _clean(data.get("title"))[:200] or None,
        }

    # لو كل ما سبق فشل: مسار tikwm المباشر (بلا علامة) بطلب كامل واحد.
    if video_id and not candidates:
        add(
            f"{TIKTOK_RESOLVER_ORIGIN}/video/media/play/{video_id}.mp4",
            "tikwm/media/play",
            whole=True,
        )

    return candidates, info, attempts

async def _tiktok_oembed(link, attempts):
    """بيانات الفيديو العامة (عنوان، غلاف، رقم الفيديو) بلا حصة ولا تسجيل."""
    try:
        response = await _fetch(
            TIKTOK_OEMBED + quote(link, safe=""),
            headers={"accept": "application/json", "user-agent": BROWSER_UA},
            redirect="follow",
        )
        if int(response.status) != 200:
            attempts.append(f"oembed -> HTTP {response.status}")
            return {}
        parsed = json.loads(await response.text())
        return parsed if isinstance(parsed, dict) else {}
    except Exception as exc:  # noqa: BLE001
        attempts.append(f"oembed -> {str(exc)[:80]}")
        return {}


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
        response = await _fetch(
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
        response = await _fetch(
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
    """طلب وسائط مع إعادة محاولة قصيرة لطلبات الازدحام فقط.

    403 يعني أن الرابط نفسه مرفوض (توقيع/كوكي) فلا فائدة من إعادة الطلب
    وضياع ميزانية المهمة؛ ننتقل إلى المرشّح التالي فوراً.
    """
    request_headers = dict(headers)
    if range_header:
        request_headers["range"] = range_header

    status = 0
    for attempt in range(2):
        response = await _fetch(media_url, headers=request_headers, redirect="follow")
        status = int(response.status)
        if status in (200, 206):
            return response
        if status not in (429, 500, 502, 503, 504):
            break
        await asyncio.sleep(0.4 * (attempt + 1))

    raise RuntimeError(f"media HTTP {status}")


async def _fetch_whole(media_url, on_progress, headers):
    """تنزيل الملف بطلب واحد عندما يرفض السيرفر طلبات النطاق (مثل tikwm)."""
    response = await _fetch_media(media_url, headers)
    size = _size_of(response.headers)
    if size > MAX_BYTES:
        raise RuntimeError(f"reel too large ({size} bytes)")
    await on_progress(35)
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
            app_media = _safe_media_url(_field(body, "media"))
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

        try:
            await _firebase_put(
                db,
                f"reels/{reel_id}/archive.json",
                {"state": "queued", "progress": 0, "updatedAt": now},
            )
        except Exception as exc:  # noqa: BLE001 - نبلّغ التطبيق بدل 500 غامض
            return _json(
                {
                    "ok": False,
                    "error": "could not queue the archive: %s" % str(exc)[:160],
                },
                status=503,
            )

        job = self._archive(
            reel_id=reel_id,
            code=code,
            tiktok=tiktok,
            source=source,
            origin=origin,
            thumbnail=thumbnail,
            app_media=app_media if source == "tiktok" else None,
        )
        try:
            self.ctx.waitUntil(job)
        except BaseException:  # noqa: BLE001 - لو تعذّر التشغيل في الخلفية نكمل الطلب
            try:
                await job
            except BaseException:  # noqa: BLE001
                pass

        return _json({"ok": True, "id": reel_id, "state": "queued"}, status=202)

    async def _status(self, reel_id):
        reel_id = _safe_id(reel_id)
        if not reel_id:
            return _error("bad id", 404)

        archive = await _firebase_get(self._db(), f"reels/{reel_id}/archive.json")
        if archive:
            # مهمة الخلفية قد تُلغى من المنصّة بلا أثر؛ نعتبر الحالة القديمة فاشلة
            # حتى يقدر التطبيق يطلب الأرشفة من جديد بدل الانتظار بلا نهاية.
            state = _clean(archive.get("state"))
            updated = int(archive.get("updatedAt") or 0)
            age = int(time.time() * 1000) - updated
            if state in ("queued", "downloading", "storing") and age > STALE_JOB_MS:
                return _json(
                    {
                        "ok": True,
                        "id": reel_id,
                        "archive": {
                            "state": "failed",
                            "progress": int(archive.get("progress") or 0),
                            "error": "the server stopped before finishing, try again",
                        },
                    }
                )
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

    async def _archive(
        self, reel_id, code, tiktok, source, origin, thumbnail, app_media=None
    ):
        db = self._db()
        state_path = f"reels/{reel_id}/archive.json"
        started = int(time.time() * 1000)
        deadline = time.monotonic() + ARCHIVE_BUDGET_S

        def remaining():
            return deadline - time.monotonic()

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
            attempts = []
            hosts = []
            last_error = "no media url"

            # تيك توك: كل مرشّح يحمل ترويساته. روابط `*.tiktokcdn.com` التي
            # يحلّها التطبيق من شبكة الهاتف تعمل على Cloudflare، بينما روابط
            # صفحة الويب (`*-prime.tiktok.com`) ترد 403 دائماً.
            if source == "tiktok":
                base_headers = {"user-agent": BROWSER_UA, "accept": "*/*"}
            else:
                base_headers = {
                    "user-agent": CRAWLER_UA,
                    "referer": "https://www.instagram.com/",
                }

            async def on_progress(percent):
                await report("downloading", percent)

            async def attempt(items):
                """يجرّب المرشّحين بالترتيب ويرجّع بايتات الفيديو أو None."""
                nonlocal last_error
                for candidate in items:
                    if remaining() <= 6:
                        last_error = last_error or "the download budget ran out"
                        return None
                    hosts.append((urlparse(candidate["url"]).hostname or "?")[:60])
                    media_headers = dict(base_headers)
                    media_headers.update(candidate.get("headers") or {})
                    try:
                        if candidate.get("whole"):
                            return await asyncio.wait_for(
                                _fetch_whole(
                                    candidate["url"], on_progress, media_headers
                                ),
                                max(6.0, remaining()),
                            )
                        try:
                            return await asyncio.wait_for(
                                _download_bytes(
                                    candidate["url"],
                                    on_progress,
                                    media_headers,
                                    candidate.get("bytes") or 0,
                                ),
                                max(6.0, remaining()),
                            )
                        except RuntimeError as exc:
                            if "media HTTP" not in str(exc):
                                raise
                            # احتياطي: طلب كامل واحد بدل النطاقات.
                            return await asyncio.wait_for(
                                _fetch_whole(
                                    candidate["url"], on_progress, media_headers
                                ),
                                max(6.0, remaining()),
                            )
                    except Exception as exc:  # noqa: BLE001 - نجرّب الرابط التالي
                        last_error = str(exc)[:120] or exc.__class__.__name__
                return None

            async def resolve(budget):
                nonlocal info, attempts
                try:
                    if source == "tiktok":
                        found, resolved_info, log = await asyncio.wait_for(
                            _resolve_tiktok(tiktok, deadline=time.monotonic() + budget),
                            budget,
                        )
                    else:
                        media, log = await asyncio.wait_for(
                            _resolve_media_url(code), budget
                        )
                        found = [{"url": media, "bytes": 0}] if media else []
                        resolved_info = {}
                    info = resolved_info or info
                    attempts = list(log) + attempts
                    return found
                except asyncio.TimeoutError:
                    attempts.append(f"{source} -> no answer in {budget:.0f}s")
                    return []

            await report("downloading", 6)

            # المسار السريع: رابط CDN النظيف القادم من التطبيق (بلا علامة مائية).
            data = None
            if app_media:
                data = await attempt(
                    [
                        {
                            "url": app_media,
                            "bytes": 0,
                            "headers": {"user-agent": BROWSER_UA, "accept": "*/*"},
                            "label": "app/media",
                        }
                    ]
                )

            if data is None:
                candidates = await resolve(max(4.0, remaining() - 12))
                if candidates:
                    data = await attempt(candidates)

            if not data:
                raise RuntimeError(
                    "media download failed: %s (hosts: %s) (%s)"
                    % (
                        last_error,
                        ", ".join(dict.fromkeys(hosts)),
                        " | ".join(attempts)[:200],
                    )
                )

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
            await report(
                "failed",
                0,
                error=("%s: %s" % (exc.__class__.__name__, exc))[:300],
            )

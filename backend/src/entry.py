"""Pigeon Reels API — Cloudflare Python Worker.

المهمة: يستقبل رابط ريل إنستغرام أو فيديو تيك توك، ينزّل الفيديو، يخزنه في
R2، ويحدّث حالة المعالجة والتقدّم في Firebase Realtime Database تحت
`/reels/<id>/archive`، ثم يقدّم الملف للتحميل من `GET /download/<id>`.

كذلك ينشر الريلز المجدولة من `/schedules/<id>` إلى إنستغرام عند حلول موعدها
(19:00 بتوقيت الجزائر) عبر Instagram Graph API، من cron داخل الـWorker، لا من
جدولة إنستغرام نفسها. الملف العام الذي يقرأه Meta من `GET /media/<id>`.

المسارات:
    GET  /                     معلومات الخدمة
    GET  /health               فحص سريع
    POST /reels                {"id", "url", "thumbnail"?} -> 202 ويبدأ المعالجة
    GET  /reels/<id>           حالة الأرشفة (تقرأ من Firebase، وبدونها من R2)
    GET  /download/<id>        ملف الفيديو من R2 (Content-Disposition: attachment)
    GET  /media/<id>           نفس الملف للعرض العام (لـMeta، بلا مفتاح)
    GET  /schedules            وقت الخادم والوقت المتبقي وحالة كل موعد
    POST /schedules            {"id", "at"} -> يكتب الموعد ويزيحه لو تداخل
    POST /publish-now          {"id"} -> ينشر الريل الآن (يتجاهل الموعد) ثم يحذف وينظّف
    GET|POST /publish          تشغيل المجدول فوراً (احتياط للـcron)

كل الطلبات تُرفض إذا لم يكن `API_TOKEN` مضبوطاً في الأسرار، ولم يكن الترويسة
`X-Api-Key` مطابقة، ما عدا `/media/<id>` و`/health`.
"""

import asyncio
import json
import re
import time
from datetime import datetime
from urllib.parse import quote, urlencode, urlparse

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

# النشر إلى إنستغرام من نفس الـWorker (لا من جدولة إنستغرام).
IG_GRAPH = "https://graph.facebook.com/v23.0"
IG_DEFAULT_USER_ID = "17841438848736931"
IG_DEFAULT_PUBLIC_BASE = "https://brainrot-pigeon-reels.brainrotpigeon.workers.dev"

# عدد محاولات cron قبل اعتبار الريل فاشلاً (cron كل دقيقة ≈ ساعة انتظار).
SCHEDULE_RETRY_LIMIT = 60

# أقصى عدد مواعيد تُعالج في تشغيل واحد حتى تبقى مدة cron قصيرة.
MAX_PUBLISH_PER_RUN = 3

# المواعيد والعدّ التنازلي ملك الخادم وحده: التطبيق يقرأ الساعة والوقت المتبقي
# من هنا (GET /schedules) ولا يحسب موعداً بنفسه.
SCHEDULE_TIMEZONE = "Africa/Algiers"

# موعدان أقرب من هذا يُعتبران متداخلين، فيُزح الموعد الجديد لخانة حرة بعده.
MIN_SLOT_GAP_MS = 5 * 60 * 1000

# ريل واحد ينشر في المرة: بعد بدء media_publish لا يبدأ ريل آخر قبل هذه المدة،
# حتى لا ينشر ريلان في نفس اللحظة على الحساب.
PUBLISH_GAP_MS = 2 * 60 * 1000
PUBLISH_BUSY = "waiting for the reel that is publishing now"
PUBLISH_LOCK_PATH = "worker/publishLock.json"

# "انشر الآن" ينفّذ السلسلة كلها داخل الطلب نفسه: أرشفة إن لزم، إنشاء الحاوية،
# انتظار جاهزيتها عند Meta، media_publish، تأكيد، ثم حذف الملف والتنظيف.
# الميزانية زمنية (نوم لا حساب) لأن معالجة الفيديو عند Meta تحتاج دقائق أحياناً،
# والمهلة القصيرة كانت تخلّي أول ضغطة ترجع "prepared" بلا نشر.
PUBLISH_NOW_BUDGET_S = 90
PUBLISH_NOW_POLL_S = 2.5

# نُجهّز حاوية إنستغرام قبل الموعد: إنشاء الحاوية من الفيديو يستغرق دقائق عند
# Meta، ولو انتظرنا لحظة الموعد لظهر الريل متأخراً عن الوقت المعروض في التطبيق.
PREPARE_LEAD_MS = 45 * 60 * 1000

# --------------------------------------------------------------------------
# سلسلة النشر: حالات ثابتة بالترتيب، ولكل خطوة مساحتها وحدها
# --------------------------------------------------------------------------
# كل حالة تعني: "الخطوة السابقة انتهت بنتيجة، وهذه الخطوة هي التي يحق لها العمل
# الآن". الانتقال بين الحالات لا يتم إلا بكتابة مشروطة (if-match ETag)، فلو كان
# تشغيلان يعملان في نفس اللحظة يفشل الثاني (412) ولا يتكرر النشر ولا يُحذف ملف
# تحت إنستغرام وهو ما زال ينشر.
STAGE_SCHEDULED = "scheduled"  # بانتظار الموعد
STAGE_PREPARING = "preparing"  # إنشاء حاوية إنستغرام
STAGE_PREPARED = "prepared"  # الحاوية جاهزة، بانتظار الموعد
STAGE_PUBLISHING = "publishing"  # media_publish في الطريق
STAGE_VERIFYING = "verifying"  # التأكد أن الريل ظهر فعلاً على الحساب
STAGE_CLEANING = "cleaning"  # حذف الملف ثم سجلات Firebase بعد التأكيد
STAGE_PUBLISHED = "published"  # نجاح نهائي (يبقى فقط لو تعذّر التنظيف)
STAGE_UNVERIFIED = "unverified"  # نُشر؟ غير مؤكد: لا يُحذف الملف أبداً
STAGE_FAILED = "failed"  # فشل نهائي مع سبب واضح

# رسالة خاصة: الخطوة تنتظر ملفاً لم يصل R2 بعد، وليست محاولة فاشلة.
ARCHIVE_WAIT = "waiting for the reel archive"

# حدود المحاولات لكل خطوة على حدة.
PREPARE_RETRY_LIMIT = 10
PUBLISH_RETRY_LIMIT = 5
VERIFY_RETRY_LIMIT = 10
CLEAN_RETRY_LIMIT = 10

# حجز الخطوة: لو مات التشغيل في منتصفها تبقى محجوزة حتى هذه المدة ثم يستأنفها
# التشغيل القادم من حيث توقفت.
STAGE_LEASE_MS = 2 * 60 * 1000

# الفاصل بين نجاح النشر وحذف الملف من التخزين (يُتحقق من النشر قبله وبعده).
CLEANUP_DELAY_MS = 5 * 1000

# media_publish ينشر الريل فعلًا في ثوانٍ لكن استجابته قد تتأخر؛ المهلة القصيرة
# كانت تجعل الـWorker يظن أن النشر فشل والريل منشور أصلًا.
PUBLISH_TIMEOUT_S = 30
GRAPH_READ_TIMEOUT_S = 15

# لو توقّف الـWorker بعد إرسال media_publish وقبل كتابة النتيجة، لا نعيد النشر
# تلقائياً حتى لا يتكرر الريل؛ ننتظر هذه المدة ثم نعلّم الحالة فاشلة للمراجعة.
PUBLISH_STUCK_MS = 10 * 60 * 1000

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


def _int_of(data, name, default=0):
    """حقل رقمي من سجل Firebase، وأي قيمة غائبة أو غير رقمية تعطي [default]."""
    try:
        value = data.get(name)
    except Exception:  # noqa: BLE001 - ليس dict
        return default
    if value is None or value == "":
        return default
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _stage_of(schedule):
    """حالة الموعد الحالية في سلسلة النشر (scheduled افتراضاً)."""
    try:
        state = schedule.get("state")
    except Exception:  # noqa: BLE001 - ليس dict
        state = None
    return _clean(state, STAGE_SCHEDULED)


def _path_of(raw_url):
    return urlparse(raw_url).path or "/"


def _origin_of(raw_url):
    parsed = urlparse(raw_url)
    return f"{parsed.scheme}://{parsed.netloc}"


async def _fetch(url, **options):
    """طلب شبكة بمهلة قصوى، لأن fetch في الـWorker بلا مهلة افتراضية."""
    timeout = options.pop("timeout", None) or FETCH_TIMEOUT_S
    return await asyncio.wait_for(fetch(url, **options), timeout)


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


async def _object_exists(env, key):
    """هل الملف موجود في R2؟ True/False، أو None إذا تعذّر الفحص.

    الفحص نفسه ليس شرطاً للنشر: Meta تنزّل الرابط بنفسها، فلو فشل `head`
    نكمل ونترك القرار للحاوية بدل أن يتوقف الموعد بلا نهاية كل 5 دقائق.
    """
    try:
        obj = await env.FILES.head(key)
    except Exception:  # noqa: BLE001 - بعض النسخ لا توفّر head أو تفشل الشبكة
        return None
    return obj is not None


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


async def _firebase_patch(base, path, payload):
    """تحديث جزئي: لا يمسح حقول الموعد التي لم نرسلها (مثل at/timezone)."""
    body = json.dumps(payload, ensure_ascii=False)
    response = await _fetch(
        f"{base.rstrip('/')}/{path.lstrip('/')}",
        method="PATCH",
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


async def _firebase_read(base, path):
    """يقرأ السجل مع بصمته (ETag): بها نمنع أي كتابة متداخلة على نفس الموعد."""
    response = await _fetch(
        f"{base.rstrip('/')}/{path.lstrip('/')}",
        headers={"accept": "application/json", "x-firebase-etag": "true"},
    )
    etag = None
    try:
        etag = _clean(response.headers.get("etag")) or None
    except Exception:  # noqa: BLE001 - استجابة بلا ترويسات
        etag = None
    if int(response.status) != 200:
        return None, etag
    text = await response.text()
    if not text or text == "null":
        return None, etag
    try:
        return json.loads(text), etag
    except Exception:  # noqa: BLE001
        return None, etag


async def _firebase_compare_write(base, path, etag, current, payload):
    """كتابة مشروطة: تنجح فقط إن لم يتغيّر السجل منذ قراءته (412 يعني سبقنا أحد).

    Firebase RTDB يرفض ترويسة `if-match` على PATCH (يرد 400: "The 'if-match'
    header is not supported with GET, PATCH or POST requests")، ويقبلها على PUT
    فقط. لذلك ندمج الحقول الجديدة فوق السجل الذي قرأناه ثم نكتبه بـPUT، وبما
    أن `if-match` يضمن أن السجل لم يتغيّر بين القراءة والكتابة فالدمج آمن.
    """
    if not etag:
        return 0
    body = dict(current) if isinstance(current, dict) else {}
    body.update(payload)
    response = await _fetch(
        f"{base.rstrip('/')}/{path.lstrip('/')}",
        method="PUT",
        headers={
            "content-type": "application/json; charset=utf-8",
            "if-match": etag,
        },
        body=json.dumps(body, ensure_ascii=False),
    )
    return int(response.status)


async def _firebase_delete(base, path):
    """حذف عقدة كاملة من Firebase (DELETE)."""
    response = await _fetch(
        f"{base.rstrip('/')}/{path.lstrip('/')}",
        method="DELETE",
        headers={"accept": "application/json"},
    )
    return int(response.status)


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
# نشر إنستغرام (Graph API)
# --------------------------------------------------------------------------


def _ig_user(env):
    return _clean(getattr(env, "IG_USER_ID", None), IG_DEFAULT_USER_ID)


def _ig_token(env):
    return _clean(getattr(env, "IG_ACCESS_TOKEN", None))


def _ig_public_base(env):
    return _clean(
        getattr(env, "PUBLIC_BASE_URL", None), IG_DEFAULT_PUBLIC_BASE
    ).rstrip("/")


def _ig_message(payload, status):
    error = payload.get("error") if isinstance(payload, dict) else None
    if isinstance(error, dict):
        text = error.get("error_user_msg") or error.get("message") or "instagram error"
        code = error.get("code")
        sub = error.get("error_subcode")
        return f"instagram {status}: {str(text)[:220]} (code {code}/{sub})"
    return f"instagram HTTP {status}"


async def _ig_json(response):
    text = await response.text()
    if not text:
        return {}
    try:
        parsed = json.loads(text)
    except Exception:  # noqa: BLE001
        return {"raw": text[:300]}
    return parsed if isinstance(parsed, dict) else {"raw": text[:300]}


def _describe(exc):
    """رسالة الخطأ؛ بعض استثناءات المهلة لا تحمل نصاً فنستخدم نوعها."""
    text = str(exc).strip()
    return text or exc.__class__.__name__


def _truthy(value):
    """حقل منطقي من Firebase: قد يوصل bool كما هو أو كنص."""
    if isinstance(value, bool):
        return value
    return _clean(value).lower() in ("1", "true", "yes")


async def _ig_published_media(user_id, token, since_ms):
    """هل نُشر ريل جديد على الحساب بعد `since_ms`؟ يرجّع معرّفه أو None.

    استدعاء media_publish قد ينشر الريل ثم تتأخر الاستجابة أو تضيع، فلا يصح
    الحكم بالفشل قبل السؤال عن آخر опублиك على الحساب.
    """
    query = urlencode(
        {"fields": "id,timestamp", "limit": "5", "access_token": token}
    )
    try:
        response = await _fetch(
            f"{IG_GRAPH}/{user_id}/media?{query}", timeout=GRAPH_READ_TIMEOUT_S
        )
    except Exception:  # noqa: BLE001 - فحص احتياطي فقط
        return None
    if int(response.status) != 200:
        return None
    data = await _ig_json(response)
    items = data.get("data")
    if not isinstance(items, list):
        return None

    for item in items:
        if not isinstance(item, dict):
            continue
        try:
            published = int(
                datetime.strptime(
                    _clean(item.get("timestamp")), "%Y-%m-%dT%H:%M:%S%z"
                ).timestamp()
                * 1000
            )
        except Exception:  # noqa: BLE001 - نسخة بلا وقت صالح
            continue
        # هامش دقيقتين لتعديلات توقيت Meta.
        if published >= since_ms - 120_000:
            media_id = _clean(item.get("id"))
            if media_id:
                return media_id
    return None


async def _ig_media_exists(user_id, token, media_id):
    """هل المنشور موجود فعلاً على الحساب؟ True / False، أو None إذا تعذّر الفحص.

    نستعملها قبل حذف ملف R2: لا يُحذف فيديو إلا وInstagram يؤكد أن منشوره قائم.
    """
    query = urlencode(
        {"fields": "id,media_type,permalink", "access_token": token}
    )
    try:
        response = await _fetch(
            f"{IG_GRAPH}/{media_id}?{query}", timeout=GRAPH_READ_TIMEOUT_S
        )
    except Exception:  # noqa: BLE001 - الفحص غير مؤكد
        return None
    status = int(response.status)
    if status == 200:
        data = await _ig_json(response)
        return bool(_clean(data.get("id")))
    if status in (400, 404):
        return False
    return None


async def _ig_create_container(user_id, token, media_url, caption):
    """ينشئ Reels media container من رابط عام؛ Meta تنزّل الفيديو بنفسها."""
    payload = {
        "media_type": "REELS",
        "video_url": media_url,
        "share_to_feed": "true",
        "access_token": token,
    }
    if caption:
        payload["caption"] = caption

    response = await _fetch(
        f"{IG_GRAPH}/{user_id}/media",
        method="POST",
        headers={"content-type": "application/x-www-form-urlencoded"},
        body=urlencode(payload),
        timeout=GRAPH_READ_TIMEOUT_S + 10,
    )
    data = await _ig_json(response)
    if int(response.status) != 200 or not data.get("id"):
        raise RuntimeError(_ig_message(data, int(response.status)))
    return str(data["id"])


async def _ig_container_status(container_id, token):
    """يرجّع (status_code, status) للحاوية: IN_PROGRESS / FINISHED / ERROR…"""
    query = urlencode({"fields": "status_code,status", "access_token": token})
    response = await _fetch(
        f"{IG_GRAPH}/{container_id}?{query}", timeout=GRAPH_READ_TIMEOUT_S
    )
    data = await _ig_json(response)
    if int(response.status) != 200:
        raise RuntimeError(_ig_message(data, int(response.status)))
    return (
        _clean(data.get("status_code"), "UNKNOWN"),
        _clean(data.get("status")),
    )


async def _ig_publish(user_id, token, container_id):
    response = await _fetch(
        f"{IG_GRAPH}/{user_id}/media_publish",
        method="POST",
        headers={"content-type": "application/x-www-form-urlencoded"},
        body=urlencode({"creation_id": container_id, "access_token": token}),
        timeout=PUBLISH_TIMEOUT_S,
    )
    data = await _ig_json(response)
    if int(response.status) != 200 or not data.get("id"):
        raise RuntimeError(_ig_message(data, int(response.status)))
    return str(data["id"])


# --------------------------------------------------------------------------
# الـWorker
# --------------------------------------------------------------------------


class Default(WorkerEntrypoint):
    """نقطة الدخول: راوتر صغير للمسارات + خلفية الأرشفة + cron النشر."""

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
                    "version": 10,
                    "routes": [
                        "POST /reels",
                        "GET /reels/<id>",
                        "DELETE /reels/<id>",
                        "GET /download/<id>",
                        "GET /media/<id>",
                        "GET|POST /publish",
                        "GET /schedules",
                        "POST /schedules",
                        "POST /publish-now",
                    ],
                    "sources": ["instagram", "tiktok"],
                    "publishing": "instagram graph api (server countdown)",
                }
            )

        if parts[0] == "health":
            return _json({"ok": True, "time": int(time.time() * 1000)})

        # ملف عام يقرأه Meta وقت النشر: بلا X-Api-Key لأن Graph API لا يرسله.
        if parts[0] == "media" and len(parts) == 2 and method in ("GET", "HEAD"):
            return await self._media(parts[1], method)

        if not self._authorized(request):
            return _error("unauthorized", 401)

        if parts[0] == "reels" and len(parts) == 1 and method == "POST":
            return await self._start(request)

        if parts[0] == "reels" and len(parts) == 2 and method == "GET":
            return await self._status(parts[1])

        # حذف ريل مجدول من التطبيق: يمسح ملفه من R2 وكل معلوماته من Firebase.
        if parts[0] == "reels" and len(parts) == 2 and method == "DELETE":
            return await self._delete_reel(parts[1])

        if parts[0] == "download" and len(parts) == 2 and method == "GET":
            return await self._download(parts[1])

        # مصدر الحقيقة الوحيد للعدّ التنازلي: وقت الخادم والوقت المتبقي لكل ريل.
        if parts[0] == "schedules" and len(parts) == 1 and method == "GET":
            return _json(await self._schedule_snapshot())

        # الجدولة تُكتب من هنا لا من التطبيق، فيملك الخادم الموعد والعدّ التنازلي.
        if parts[0] == "schedules" and len(parts) == 1 and method == "POST":
            return await self._set_schedule(request)

        # النشر الآن من التطبيق: العدّاد يُلغى فوراً ثم تتولى السلسلة العادية
        # بقية الخطوات (نشر، تأكيد، تنظيف) كما لو أن الموعد حلّ هذه اللحظة.
        if parts[0] == "publish-now" and len(parts) == 1 and method == "POST":
            return await self._publish_now(request)

        # تشغيل المجدول فوراً بدل انتظار cron: يرجّع تقريراً كاملاً لكل موعد
        # (الحالة، سبب الخطأ، وهل التوكن مضبوط) حتى يمكن تشخيص النشر من المتصفح.
        if parts[0] == "publish" and len(parts) == 1 and method in ("GET", "POST"):
            summary = await self._publish_due()
            await self._record_run(summary)
            return _json(summary)

        if method == "OPTIONS":
            return Response(
                "",
                status=204,
                headers={
                    "access-control-allow-origin": "*",
                    "access-control-allow-headers": "content-type,x-api-key",
                    "access-control-allow-methods": "GET,POST,DELETE,OPTIONS",
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

        # نحفظ الرابط الذي حلّه الهاتف حتى يستطيع cron إعادة الأرشفة بنفسه
        # لو انقطعت الأرشفة الأولى قبل أن يُخزّن الملف في R2.
        if app_media:
            try:
                await _firebase_patch(
                    db,
                    f"reels/{reel_id}.json",
                    {"media": app_media, "source": source},
                )
            except Exception:  # noqa: BLE001 - تحسين فقط
                pass

        job = self._archive(
            reel_id=reel_id,
            code=code,
            tiktok=tiktok,
            source=source,
            origin=origin,
            thumbnail=thumbnail,
            app_media=app_media if source == "tiktok" else None,
        )
        # ننتظر الأرشفة داخل الطلب: `waitUntil` تُلغى بعد انتهاء الاستجابة،
        # وهذا هو سبب أن الملف كان لا يصل R2 إلا عند إعادة الطلب من زر التنزيل.
        try:
            await asyncio.wait_for(job, ARCHIVE_BUDGET_S + 8)
        except BaseException:  # noqa: BLE001 - نكمل بالحالة المسجلة
            pass

        stored = await _object_exists(self.env, self._key(reel_id))
        if stored is True:
            return _json({"ok": True, "id": reel_id, "state": "ready"}, status=202)

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

        if await _object_exists(self.env, self._key(reel_id)) is True:
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

    async def _media(self, reel_id, method="GET"):
        """نسخة عامة من الملف لـMeta: بلا Content-Disposition: attachment."""
        reel_id = _safe_id(reel_id)
        if not reel_id:
            return _error("bad id", 404)

        headers = {
            "content-type": "video/mp4",
            "content-disposition": f'inline; filename="pigeon-{reel_id}.mp4"',
            "cache-control": "public, max-age=86400",
            "access-control-allow-origin": "*",
        }

        # HEAD لا يحتاج جسم الملف: نقرأ البيانات الوصفية فقط.
        if method == "HEAD":
            obj = await self.env.FILES.head(self._key(reel_id))
            if obj is None:
                return _error("not stored yet", 404)
            size = getattr(obj, "size", None)
            if size is not None:
                headers["content-length"] = str(size)
            return Response("", headers=headers)

        obj = await self.env.FILES.get(self._key(reel_id))
        if obj is None:
            return _error("not stored yet", 404)
        # Meta تحتاج content-length صريحاً لتقبل تنزيل الفيديو من هذا الرابط.
        size = getattr(obj, "size", None)
        if size is not None:
            headers["content-length"] = str(size)
        headers["accept-ranges"] = "bytes"
        return Response(obj.body, headers=headers)

    def _key(self, reel_id):
        return f"reels/{reel_id}.mp4"

    # ----------------------------------------------------------------------
    # cron: نشر الريلز التي حان موعدها من Cloudflare إلى إنستغرام
    # ----------------------------------------------------------------------

    async def scheduled(self, controller, env, ctx):
        """يُستدعى من cron في wrangler.jsonc كل 5 دقائق."""
        try:
            summary = await self._publish_due()
        except Exception as exc:  # noqa: BLE001 - لا نُفشل الـcron كله
            summary = {"ok": False, "error": str(exc)[:300]}
            print(f"pigeon schedule run failed: {exc}")
        await self._record_run(summary)

    async def _record_run(self, summary):
        """يحفظ نتيجة آخر تشغيل تحت `/worker/status` لتظهر من أي متصفح."""
        try:
            # PUT على العقدة الفرعية حتى لا تبقى حقول من تشغيل سابق.
            await _firebase_put(self._db(), "worker/status/lastRun.json", summary)
            await _firebase_patch(
                self._db(),
                "worker/status.json",
                {"updatedAt": int(time.time() * 1000)},
            )
        except Exception:  # noqa: BLE001 - تشخيص فقط، لا نُفشل التشغيل
            pass

    async def _archive_missing(self, db):
        """يؤرشف ريلاً مجدولاً ما زال ملفه غائباً عن R2.

        الأرشفة وقت المشاركة قد لا تكتمل (الهاتف أغلق التطبيق أو المهمة أُلغيت)،
        فيصل الموعد بلا فيديو في التخزين ولا ينشر شيء. نعيد المحاولة من cron
        قبل الموعد بوقت كافٍ.
        """
        schedules = await _firebase_get(db, "schedules.json") or {}
        if not isinstance(schedules, dict):
            return 0

        candidates = []
        for schedule_id, schedule in schedules.items():
            if not isinstance(schedule, dict):
                continue
            try:
                at = int(schedule.get("at") or 0)
            except (TypeError, ValueError):
                continue
            if at <= 0:
                continue
            if _stage_of(schedule) not in (
                STAGE_SCHEDULED,
                STAGE_PREPARING,
                STAGE_PREPARED,
            ):
                continue
            if _clean(schedule.get("containerId")):
                continue  # الحاوية موجودة: الملف سبق أن وصل R2
            candidates.append((at, schedule_id))

        candidates.sort()
        started = 0
        for _at, schedule_id in candidates[:1]:
            clean_id = _safe_id(schedule_id)
            if not clean_id:
                continue
            if await _object_exists(self.env, self._key(clean_id)) is True:
                continue
            reel = await _firebase_get(db, f"reels/{clean_id}.json") or {}
            if not isinstance(reel, dict):
                continue
            link = _clean(reel.get("url"))
            if not link:
                continue
            code = _instagram_code(link)
            tiktok = None if code else _tiktok_link(link)
            if not code and not tiktok:
                continue
            await self._archive(
                reel_id=clean_id,
                code=code,
                tiktok=tiktok,
                source="instagram" if code else "tiktok",
                origin=_ig_public_base(self.env),
                thumbnail=_clean(reel.get("thumbnail")) or None,
                app_media=_safe_media_url(reel.get("media")),
            )
            started += 1
        return started

    async def _publish_due(self):
        """يمرّ على المواعيد وينفّذ لكل موعد خطوة واحدة من سلسلته.

        لا يوجد "نشر مباشر" هنا: الموعد يتحرك في حالاته بالترتيب، وكل تشغيل
        يبدأ من الحالة التي انتهت عندها الخطوة السابقة (نجاحاً أو فشلاً).
        """
        db = self._db()
        now = int(time.time() * 1000)
        report = {
            "ok": True,
            "checkedAt": now,
            "tokenPresent": bool(_ig_token(self.env)),
            "total": 0,
            "due": 0,
            "results": [],
            "archived": 0,
        }

        schedules = await _firebase_get(db, "schedules.json") or {}
        if not isinstance(schedules, dict):
            report["ok"] = False
            report["error"] = "the /schedules node is not an object"
            return report

        report["total"] = len(schedules)
        work = []
        for schedule_id, schedule in schedules.items():
            if not isinstance(schedule, dict):
                continue
            state = _stage_of(schedule)
            # الحالات النهائية لا تُعاد تلقائياً أبداً.
            if state in (STAGE_PUBLISHED, STAGE_FAILED, STAGE_UNVERIFIED):
                continue
            at = _int_of(schedule, "at")
            if at <= 0:
                continue
            work.append((at, _safe_id(schedule_id), schedule))

        work.sort(key=lambda entry: entry[0])
        report["due"] = len(work)

        for at, clean_id, schedule in work[:MAX_PUBLISH_PER_RUN]:
            if not clean_id:
                continue
            entry = {"id": clean_id, "at": at, "state": _stage_of(schedule)}
            try:
                state, error = await self._advance(db, clean_id, schedule, now)
                entry["state"] = state
                if error:
                    entry["error"] = error
            except Exception as exc:  # noqa: BLE001 - لا نُسقط بقية المواعيد
                print(f"pigeon schedule {clean_id} failed: {exc}")
                entry["error"] = str(exc)[:300]
            report["results"].append(entry)

        # تشغيل واحد لأرشفة ريل ناقص: إما لأن لا موعد مستحقاً، أو لأن موعداً
        # وصل إلى خطوة التجهيز وملفه ما زال غائباً عن R2.
        waiting_archive = any(
            entry.get("error") == ARCHIVE_WAIT for entry in report["results"]
        )
        if not work or waiting_archive:
            try:
                report["archived"] = await self._archive_missing(db)
            except Exception as exc:  # noqa: BLE001 - ليس حرجاً
                print(f"pigeon archive retry failed: {exc}")

        return report

    # ----------------------------------------------------------------------
    # العدّ التنازلي: يبدأ وينتهي على الخادم، والتطبيق يعرضه فقط
    # ----------------------------------------------------------------------

    async def _schedule_snapshot(self):
        """لقطة واحدة يقرؤها التطبيق: وقت الخادم والوقت المتبقي لكل ريل.

        كل الأعداد تُحسب بساعة الخادم هنا، فلا يهم أن ساعة الهاتف مضبوطة أو
        لا: العدّاد الذي يراه المالك هو عدّاد الخادم نفسه، و"publishing" حالة
        حقيقية من السجل لا تقدير من التطبيق.
        """
        db = self._db()
        now = int(time.time() * 1000)
        snapshot = {
            "ok": True,
            "serverTime": now,
            "timezone": SCHEDULE_TIMEZONE,
            "reels": {},
            "publishing": None,
        }

        schedules = await _firebase_get(db, "schedules.json") or {}
        if not isinstance(schedules, dict):
            snapshot["ok"] = False
            snapshot["error"] = "the /schedules node is not an object"
            return snapshot

        pending = []
        for schedule_id, schedule in schedules.items():
            if not isinstance(schedule, dict):
                continue
            clean_id = _safe_id(schedule_id)
            if not clean_id:
                continue

            at = _int_of(schedule, "at")
            state = _stage_of(schedule)
            snapshot["reels"][clean_id] = {
                "at": at,
                "state": state,
                "serverTime": now,
                "remainingMs": max(0, at - now) if at > 0 else 0,
                "error": _clean(schedule.get("error")) or None,
                "publishedAt": _int_of(schedule, "publishedAt"),
                "mediaId": _clean(schedule.get("mediaId")) or None,
            }

            if state in (STAGE_PUBLISHING, STAGE_VERIFYING, STAGE_CLEANING):
                snapshot["publishing"] = {
                    "id": clean_id,
                    "state": state,
                    "since": _int_of(schedule, "stageAt"),
                }
            if at > 0 and state not in (
                STAGE_PUBLISHED,
                STAGE_FAILED,
                STAGE_UNVERIFIED,
            ):
                pending.append((at, clean_id))

        # ترتيب النشر: 1 = الريل القادم، ثم الذي بعده، بلا تكرار درجات.
        pending.sort()
        for index, (_, clean_id) in enumerate(pending):
            snapshot["reels"][clean_id]["order"] = index + 1

        return snapshot

    async def _set_schedule(self, request):
        """يكتب الموعد من الخادم: الوقت والتوقيت وبداية الحالة scheduled.

        الموعد المتداخل مع موعد ريل آخر يُزح لخانة حرة بعده، فيبقى لكل ريل
        وقت نشر خاص به مهما جدول المالك عدة ريلز.
        """
        try:
            body = await request.json()
            schedule_id = _safe_id(_field(body, "id"))
            at = int(_field(body, "at") or 0)
            timezone = _clean(_field(body, "timezone"), SCHEDULE_TIMEZONE)
        except Exception:
            return _error("invalid json body")

        if not schedule_id:
            return _error("missing id")

        db = self._db()
        now = int(time.time() * 1000)
        if at <= now + 30_000:
            return _error("pick a time in the future", 400)

        # الموعد على بداية دقيقة كاملة: دورة النشر تعمل في الثانية صفر من كل
        # دقيقة، فيبدأ النشر عند صفر العدّاد نفسه لا في الدورة التي بعدها.
        settled = at - (at % 60_000)
        if settled <= now + 30_000:
            settled += 60_000

        reel = await _firebase_get(db, f"reels/{schedule_id}.json")
        if not isinstance(reel, dict):
            return _error("this reel is not registered on the server", 404)

        schedules = await _firebase_get(db, "schedules.json") or {}
        if not isinstance(schedules, dict):
            schedules = {}

        current = schedules.get(schedule_id)
        if isinstance(current, dict):
            state = _stage_of(current)
            if state in (
                STAGE_PUBLISHING,
                STAGE_VERIFYING,
                STAGE_CLEANING,
                STAGE_UNVERIFIED,
            ):
                return _error("this reel is being published right now", 409)
            if state == STAGE_PUBLISHED or _int_of(current, "publishedAt") > 0:
                return _error("this reel was already published", 409)

        # المواعيد المشغولة: كل ريل آخر ما زال في الطريق إلى النشر.
        taken = []
        for other_id, other in schedules.items():
            if _safe_id(other_id) == schedule_id or not isinstance(other, dict):
                continue
            if _stage_of(other) in (STAGE_PUBLISHED, STAGE_FAILED, STAGE_UNVERIFIED):
                continue
            other_at = _int_of(other, "at")
            if other_at > 0:
                taken.append(other_at)
        taken.sort()

        for _ in range(len(taken) + 1):
            moved = False
            for other_at in taken:
                if abs(settled - other_at) < MIN_SLOT_GAP_MS:
                    settled = other_at + MIN_SLOT_GAP_MS
                    moved = True
            if not moved:
                break

        payload = {
            "at": settled,
            "timezone": timezone or SCHEDULE_TIMEZONE,
            "state": STAGE_SCHEDULED,
            "attempts": 0,
            "error": None,
            "containerId": None,
            "stageAt": 0,
            "scheduledAt": now,
            "updatedAt": now,
        }
        status = await _firebase_patch(
            db, f"schedules/{schedule_id}.json", payload
        )
        if status != 200:
            return _error("could not save the schedule (firebase %d)" % status, 502)

        return _json(
            {
                "ok": True,
                "id": schedule_id,
                "at": settled,
                "state": STAGE_SCHEDULED,
                "timezone": payload["timezone"],
                "serverTime": now,
                "remainingMs": max(0, settled - now),
                "shifted": settled != at,
            }
        )

    async def _publish_now(self, request):
        """ينشر الريل الآن ويتجاهل الموعد: سلسلة كاملة داخل هذا الطلب.

        الخطوات بالترتيب: تأكيد أن الفيديو في R2 (وأرشفته فوراً لو ما وصل)،
        إنشاء حاوية إنستغرام الآن بلا انتظار مهلة التجهيز المسبق، إلغاء العدّاد
        (at = الآن)، ثم الانتظار حتى تصير الحاوية FINISHED عند Meta والنشر
        والتأكيد وحذف الملف من R2 وسجلات Firebase.

        السبب: الضغطة الأولى كانت تنتهي بعد ~16 ثانية والحاوية ما زالت تُعالج،
        فترجع الحالة prepared بلا نشر، ولا ينشر إلا بعد ضغطة ثانية بعد دقائق.
        """
        try:
            body = await request.json()
            schedule_id = _safe_id(_field(body, "id"))
        except Exception:
            return _error("invalid json body")

        if not schedule_id:
            return _error("missing id")

        db = self._db()
        now = int(time.time() * 1000)
        path = f"schedules/{schedule_id}.json"
        current, _etag = await _firebase_read(db, path)
        if not isinstance(current, dict):
            return _error("this reel has no schedule on the server", 404)

        state = _stage_of(current)
        if state in (STAGE_PUBLISHING, STAGE_VERIFYING, STAGE_CLEANING):
            return _error("this reel is being published right now", 409)
        if state == STAGE_PUBLISHED or _int_of(current, "publishedAt") > 0:
            return _error("this reel was already published", 409)
        if state == STAGE_UNVERIFIED:
            return _error("the last publish is unconfirmed; check Instagram first", 409)

        # ريل واحد ينشر في المرة، لكن الضغط اليدوي لا ينتظر فاصل الدقيقتين.
        if not await self._publish_slot_free(db, schedule_id, now, ignore_gap=True):
            return _error(PUBLISH_BUSY, 409)

        # 1) الفيديو في التخزين: لو الأرشفة وقت المشاركة ما كملت، تؤرشف الآن.
        if await _object_exists(self.env, self._key(schedule_id)) is not True:
            if not await self._archive_now(db, schedule_id):
                return _error(
                    "the reel could not be archived yet, try again", 503
                )

        # 2) حاوية إنستغرام: تُنشأ الآن، لا تُترك لخطوة التجهيز المجدولة.
        container_id = _clean(current.get("containerId"))
        if not container_id:
            container_id, error = await self._prepare_container(schedule_id)
            if not container_id:
                await _firebase_patch(
                    db,
                    path,
                    {"error": error, "updatedAt": int(time.time() * 1000)},
                )
                return _json(
                    {
                        "ok": False,
                        "id": schedule_id,
                        "state": state,
                        "error": error or "instagram did not return a container id",
                        "serverTime": int(time.time() * 1000),
                        "remainingMs": 0,
                    },
                    status=503,
                )

        # 3) العدّاد يُلغى: الموعد صار الآن، و"انشر الآن" يعلَّم على السجل.
        moment = int(time.time() * 1000)
        status = await _firebase_patch(
            db,
            path,
            {
                "at": moment,
                "state": STAGE_PREPARED,
                "containerId": container_id,
                "attempts": 0,
                "error": None,
                "stageAt": 0,
                "manualPublish": True,
                "manualAt": moment,
                "updatedAt": moment,
            },
        )
        if status != 200:
            return _error("could not start the publish (firebase %d)" % status, 502)

        # 4) النشر ثم التأكيد ثم الحذف والتنظيف، كلها قبل الردّ.
        state, error = await self._advance_now(db, schedule_id)
        if error == ARCHIVE_WAIT:
            error = "the reel archive is not ready on the server"

        published = state in (STAGE_PUBLISHED, STAGE_CLEANING)
        if not published and error is None:
            error = "instagram is still processing the video; press again in a minute"

        return _json(
            {
                "ok": published,
                "id": schedule_id,
                "state": state,
                "error": None if published else error,
                "serverTime": int(time.time() * 1000),
                "remainingMs": 0,
            },
            status=200 if published else 202,
        )

    async def _archive_now(self, db, schedule_id):
        """يؤرشف ريلاً فوراً لو ملفه مو في R2 بعد (نفس منطق `_archive_missing`).

        يرجّع True فقط إذا صار الملف موجوداً فعلاً في R2 بعد المحاولة.
        """
        reel = await _firebase_get(db, f"reels/{schedule_id}.json") or {}
        if not isinstance(reel, dict):
            return False

        link = _clean(reel.get("url"))
        code = _instagram_code(link)
        tiktok = None if code else _tiktok_link(link)
        if not code and not tiktok:
            return False

        await self._archive(
            reel_id=schedule_id,
            code=code,
            tiktok=tiktok,
            source="instagram" if code else "tiktok",
            origin=_ig_public_base(self.env),
            thumbnail=_clean(reel.get("thumbnail")) or None,
            app_media=_safe_media_url(reel.get("media")),
        )
        return await _object_exists(self.env, self._key(schedule_id)) is True

    async def _advance_now(self, db, schedule_id, budget_s=PUBLISH_NOW_BUDGET_S):
        """يمشي بالسلسلة فوراً بعد إلغاء العدّاد، حتى النشر والتنظيف.

        معالجة الفيديو عند Meta تحتاج ثواني إلى دقائق قبل أن تصير الحاوية
        `FINISHED`، فالمتابعة هنا زمنية (نوم بين المحاولات) لا بعدد دورات: المهلة
        القديمة (ثماني دورات ≈ 16 ثانية) كانت ترجع وفيها الحاوية ما زالت تُعالج،
        فيظهر للمالك أن "انشر الآن" ما عمل شي حتى يضغط مرة ثانية بعد دقائق.
        """
        deadline = time.monotonic() + budget_s
        state = STAGE_SCHEDULED
        error = None
        final = (STAGE_PUBLISHED, STAGE_FAILED, STAGE_UNVERIFIED)

        while time.monotonic() < deadline:
            current, _etag = await _firebase_read(db, f"schedules/{schedule_id}.json")
            if not isinstance(current, dict):
                # خطوة التنظيف مسحت السجل: النشر تمّ فعلاً وانتهى.
                if state in (STAGE_VERIFYING, STAGE_CLEANING, STAGE_PUBLISHED):
                    return STAGE_PUBLISHED, None
                return state, "the schedule record disappeared"

            state = _stage_of(current)
            if state in final:
                break

            state, error = await self._advance(
                db, schedule_id, current, int(time.time() * 1000)
            )
            if state in final:
                break
            if error in (ARCHIVE_WAIT, PUBLISH_BUSY):
                break  # لا فائدة من الانتظار: يحتاج الملف أو خانة نشر حرّة
            await asyncio.sleep(PUBLISH_NOW_POLL_S)

        return state, error

    async def _publish_slot_free(self, db, schedule_id, now, ignore_gap=False):
        """هل يحق لهذا الريل أن يبدأ النشر الآن دون أن يتداخل مع ريل آخر؟

        [ignore_gap] لنشر يدوي من المالك: يتجاهل فاصل الدقيقتين بعد نشر الريل
        السابق، لكنه لا يسمح أبداً بالتداخل مع ريل ما زال ينشر الآن.
        """
        lock = await _firebase_get(db, PUBLISH_LOCK_PATH) or {}
        if isinstance(lock, dict) and not ignore_gap:
            busy_id = _safe_id(lock.get("id"))
            started = _int_of(lock, "at")
            if (
                busy_id
                and busy_id != schedule_id
                and started > 0
                and now - started < PUBLISH_GAP_MS
            ):
                return False

        schedules = await _firebase_get(db, "schedules.json") or {}
        if not isinstance(schedules, dict):
            return True
        for other_id, other in schedules.items():
            if _safe_id(other_id) == schedule_id or not isinstance(other, dict):
                continue
            if _stage_of(other) in (STAGE_PUBLISHING, STAGE_VERIFYING, STAGE_CLEANING):
                return False
        return True

    async def _lock_publish(self, db, schedule_id, started):
        """يحجز خانة النشر لهذا الريل، فيمنع بدء نشر ريل آخر قبله مباشرة."""
        try:
            await _firebase_put(
                db, PUBLISH_LOCK_PATH, {"id": schedule_id, "at": started}
            )
        except Exception as exc:  # noqa: BLE001 - القفل تحسين، وليس شرطاً للنشر
            print(f"pigeon publish lock failed: {exc}")

    async def _advance(self, db, schedule_id, schedule, now):
        """خطوة واحدة لهذا الموعد، حسب حالته وحدها.

        كل خطوة تقرأ السجل مع بصمته (ETag) ثم تحجز نفسها بكتابة مشروطة قبل أن
        تلمس إنستغرام أو R2: لو كان تشغيل آخر يعمل على نفس الخطوة تفشل
        المطالبة (412) فلا يتداخل تشغيلان ولا يتكرر النشر.
        """
        state = _stage_of(schedule)
        # الحجز يُقرأ من stageAt وحده: updatedAt يتغيّر مع كل تحديث حالة، فلو
        # اعتمدناه لتوقف التأكيد والتنظيف دقيقتين بعد كل كتابة.
        stage_at = _int_of(schedule, "stageAt")
        busy = stage_at > 0 and now - stage_at < STAGE_LEASE_MS

        if busy and state in (
            STAGE_PREPARING,
            STAGE_PUBLISHING,
            STAGE_VERIFYING,
            STAGE_CLEANING,
        ):
            # الخطوة محجوزة لتشغيل آخر لم ينته بعد: لا نلمس شيئاً.
            return state, None

        if state == STAGE_SCHEDULED:
            at = _int_of(schedule, "at")
            if at > now and at - now > PREPARE_LEAD_MS:
                return state, None  # موعد بعيد: لا خطوة بعد
            return await self._stage_prepare(db, schedule_id)

        if state == STAGE_PREPARING:
            return await self._stage_prepare(db, schedule_id)

        if state == STAGE_PREPARED:
            if _int_of(schedule, "at") > now:
                return state, None  # الحاوية جاهزة، ننتظر الموعد
            return await self._stage_publish(db, schedule_id)

        if state == STAGE_PUBLISHING:
            return await self._stage_publish(db, schedule_id)

        if state == STAGE_VERIFYING:
            return await self._stage_verify(db, schedule_id)

        if state == STAGE_CLEANING:
            return await self._stage_clean(db, schedule_id)

        return state, None

    async def _claim(self, db, path, etag, current, to_state, extra=None):
        """يحجز خطوة: لا تنجح إلا إن كان السجل ما زال كما قرأناه (if-match)."""
        moment = int(time.time() * 1000)
        payload = {
            "state": to_state,
            "stageAt": moment,
            "updatedAt": moment,
        }
        if extra:
            payload.update(extra)
        status = await _firebase_compare_write(db, path, etag, current, payload)
        return status == 200

    async def _stage_prepare(self, db, schedule_id):
        """الخطوة 1: إنشاء حاوية إنستغرام قبل الموعد.

        نتيجتها واحدة من: prepared (نجاح) / scheduled (إعادة لاحقة) / failed.
        """
        path = f"schedules/{schedule_id}.json"
        current, etag = await _firebase_read(db, path)
        if not isinstance(current, dict):
            return STAGE_SCHEDULED, "the schedule record disappeared"

        state = _stage_of(current)
        if state not in (STAGE_SCHEDULED, STAGE_PREPARING):
            return state, None

        if _clean(current.get("containerId")):
            moment = int(time.time() * 1000)
            await _firebase_patch(
                db, path, {"state": STAGE_PREPARED, "error": None, "updatedAt": moment}
            )
            return STAGE_PREPARED, None

        # الملف ليس في R2 بعد: ننتظر الأرشفة، وهذه ليست محاولة فاشلة.
        if await _object_exists(self.env, self._key(schedule_id)) is not True:
            return STAGE_SCHEDULED, ARCHIVE_WAIT

        if not await self._claim(
            db, path, etag, current, STAGE_PREPARING, {"error": None}
        ):
            return state, None  # تشغيل آخر حجز خطوة التجهيز

        container_id, error = await self._prepare_container(schedule_id)
        moment = int(time.time() * 1000)

        if error is None and container_id:
            await _firebase_patch(
                db,
                path,
                {
                    "state": STAGE_PREPARED,
                    "containerId": container_id,
                    "preparedAt": moment,
                    "attempts": 0,
                    "error": None,
                    "stageAt": moment,
                    "updatedAt": moment,
                },
            )
            return STAGE_PREPARED, None

        attempts = _int_of(current, "attempts") + 1
        error = error or "instagram did not return a container id"
        if attempts >= PREPARE_RETRY_LIMIT:
            await _firebase_patch(
                db,
                path,
                {
                    "state": STAGE_FAILED,
                    "attempts": attempts,
                    "error": error,
                    "stageAt": moment,
                    "updatedAt": moment,
                },
            )
            return STAGE_FAILED, error

        await _firebase_patch(
            db,
            path,
            {
                "state": STAGE_SCHEDULED,
                "attempts": attempts,
                "error": error,
                "stageAt": moment,
                "updatedAt": moment,
            },
        )
        return STAGE_SCHEDULED, error

    async def _prepare_container(self, schedule_id):
        """ينشئ الحاوية فقط. يرجّع (container_id, error) ولا يكتب في Firebase."""
        token = _ig_token(self.env)
        if not token:
            return None, "IG_ACCESS_TOKEN is not configured"

        media_url = f"{_ig_public_base(self.env)}/media/{schedule_id}"
        try:
            container_id = await _ig_create_container(
                _ig_user(self.env), token, media_url, ""
            )
        except Exception as exc:  # noqa: BLE001 - يعيد المتصل المحاولة
            return None, _describe(exc)[:300]
        return container_id, None

    async def _stage_publish(self, db, schedule_id):
        """الخطوة 2: media_publish. نتيجتها: verifying / prepared / failed."""
        path = f"schedules/{schedule_id}.json"
        current, etag = await _firebase_read(db, path)
        if not isinstance(current, dict):
            return STAGE_SCHEDULED, "the schedule record disappeared"

        state = _stage_of(current)
        if state not in (STAGE_PREPARED, STAGE_PUBLISHING):
            return state, None

        token = _ig_token(self.env)
        if not token:
            moment = int(time.time() * 1000)
            await _firebase_patch(
                db,
                path,
                {
                    "state": STAGE_FAILED,
                    "error": "IG_ACCESS_TOKEN is not configured",
                    "updatedAt": moment,
                },
            )
            return STAGE_FAILED, "IG_ACCESS_TOKEN is not configured"

        user_id = _ig_user(self.env)
        moment = int(time.time() * 1000)

        # تشغيل سابق بدأ media_publish ثم انقطع: لا نعيد النشر أبداً، بل
        # نسأل الحساب أولاً إن كان الريل ظهر فعلاً.
        if state == STAGE_PUBLISHING:
            stage_at = _int_of(current, "stageAt") or moment
            published_id = await _ig_published_media(
                user_id, token, stage_at - 180_000
            )
            if published_id:
                await self._enter_verifying(
                    db, path, published_id, moment, stage_at
                )
                # نُكمل السلسلة في نفس التشغيل: تأكيد ثم تنظيف بعد 5 ثوانٍ.
                return await self._stage_verify(db, schedule_id)
            if moment - stage_at < PUBLISH_STUCK_MS:
                return state, None
            error = "publishing was interrupted; check Instagram before retrying"
            await _firebase_patch(
                db,
                path,
                {"state": STAGE_FAILED, "error": error, "updatedAt": moment},
            )
            return STAGE_FAILED, error

        container_id = _clean(current.get("containerId"))
        if not container_id:
            await _firebase_patch(
                db,
                path,
                {
                    "state": STAGE_SCHEDULED,
                    "error": "the Instagram container id is missing",
                    "updatedAt": moment,
                },
            )
            return STAGE_SCHEDULED, "the Instagram container id is missing"

        # 2أ) لا ننشر إلا حاوية FINISHED: الحاوية ما زالت تنزّل الفيديو.
        try:
            status_code, status_text = await _ig_container_status(
                container_id, token
            )
        except Exception as exc:  # noqa: BLE001 - نعيد المحاولة في التشغيل القادم
            error = _describe(exc)[:300]
            attempts = _int_of(current, "attempts") + 1
            payload = {"attempts": attempts, "error": error, "updatedAt": moment}
            if attempts >= SCHEDULE_RETRY_LIMIT:
                payload["state"] = STAGE_FAILED
            await _firebase_patch(db, path, payload)
            return _clean(payload.get("state"), state), error

        if status_code in ("ERROR", "EXPIRED"):
            error = f"instagram container {status_code.lower()}: {status_text}"[:300]
            await _firebase_patch(
                db,
                path,
                {"state": STAGE_FAILED, "error": error, "updatedAt": moment},
            )
            return STAGE_FAILED, error

        if status_code != "FINISHED":
            attempts = _int_of(current, "attempts") + 1
            payload = {"attempts": attempts, "error": None, "updatedAt": moment}
            if attempts >= SCHEDULE_RETRY_LIMIT:
                payload["state"] = STAGE_FAILED
                payload["error"] = "instagram did not finish processing the video"
            await _firebase_patch(db, path, payload)
            return _clean(payload.get("state"), state), payload.get("error")

        # 2ب) ريل واحد ينشر في المرة: لا نبدأ media_publish وريل آخر ينشر أو
        # يتحقق أو ينظف، ولا قبل مدة كافية من بدء نشر الريل السابق. النشر اليدوي
        # (manualPublish) يتجاوز الفاصل الزمني فقط، لا التداخل مع نشر جارٍ.
        manual = _truthy(current.get("manualPublish"))
        if not await self._publish_slot_free(
            db, schedule_id, moment, ignore_gap=manual
        ):
            return state, PUBLISH_BUSY

        # 2ج) نحجز خطوة النشر قبل النداء الخارجي نفسه.
        if not await self._claim(
            db, path, etag, current, STAGE_PUBLISHING, {"error": None, "attempts": 0}
        ):
            return state, None

        started = int(time.time() * 1000)
        await self._lock_publish(db, schedule_id, started)
        media_id = None
        error = None
        try:
            media_id = await _ig_publish(user_id, token, container_id)
        except Exception as exc:  # noqa: BLE001 - قد يكون النشر نجح والرد ضاع
            error = _describe(exc)[:300]

        if not media_id:
            media_id = await _ig_published_media(
                user_id, token, started - 180_000
            )

        moment = int(time.time() * 1000)
        if media_id:
            await self._enter_verifying(db, path, media_id, moment, started)
            # التأكيد والتنظيف في نفس التشغيل حتى تُحذف المخلفات بعد 5 ثوانٍ
            # من نجاح النشر، لا مع cron لاحق.
            return await self._stage_verify(db, schedule_id)

        attempts = _int_of(current, "attempts") + 1
        error = error or "media_publish returned no id"
        payload = {"attempts": attempts, "error": error, "updatedAt": moment}
        if attempts >= PUBLISH_RETRY_LIMIT:
            payload["state"] = STAGE_FAILED
        else:
            payload["state"] = STAGE_PREPARED
        await _firebase_patch(db, path, payload)
        return _clean(payload.get("state"), state), error

    async def _enter_verifying(self, db, path, media_id, moment, published_at):
        """ينقل السجل من publishing إلى verifying مع موعد التنظيف (بعد 5 ثوانٍ)."""
        if published_at <= 0 or published_at > moment:
            published_at = moment
        await _firebase_patch(
            db,
            path,
            {
                "state": STAGE_VERIFYING,
                "mediaId": media_id,
                "publishedAt": published_at,
                "cleanupAt": published_at + CLEANUP_DELAY_MS,
                "attempts": 0,
                "error": None,
                "stageAt": moment,
                "updatedAt": moment,
            },
        )
        return STAGE_VERIFYING, None

    async def _stage_verify(self, db, schedule_id):
        """الخطوة 3: تأكيد أن المنشور موجود فعلاً على الحساب (Graph API)."""
        path = f"schedules/{schedule_id}.json"
        current, etag = await _firebase_read(db, path)
        if not isinstance(current, dict):
            return STAGE_SCHEDULED, "the schedule record disappeared"

        state = _stage_of(current)
        if state != STAGE_VERIFYING:
            return state, None

        media_id = _clean(current.get("mediaId"))
        token = _ig_token(self.env)
        moment = int(time.time() * 1000)

        if not media_id or not token:
            error = "cannot confirm the published reel"
            await _firebase_patch(
                db,
                path,
                {"state": STAGE_UNVERIFIED, "error": error, "updatedAt": moment},
            )
            return STAGE_UNVERIFIED, error

        confirmed = await _ig_media_exists(
            _ig_user(self.env), token, media_id
        )
        if confirmed is True:
            if not await self._claim(
                db, path, etag, current, STAGE_CLEANING, {"error": None, "attempts": 0}
            ):
                return state, None
            published_at = _int_of(current, "publishedAt") or moment
            await _firebase_patch(
                db,
                path,
                {
                    "cleanupAt": published_at + CLEANUP_DELAY_MS,
                    "stageAt": moment,
                    "updatedAt": moment,
                },
            )
            return await self._stage_clean(db, schedule_id)

        attempts = _int_of(current, "attempts") + 1
        if confirmed is False:
            error = "instagram did not confirm the published reel"
        else:
            error = "could not confirm the published reel with instagram"
        if attempts >= VERIFY_RETRY_LIMIT:
            # بلا تأكيد لا نحذف الملف أبداً: نترك الريل والمعلومات للمراجعة.
            await _firebase_patch(
                db,
                path,
                {
                    "state": STAGE_UNVERIFIED,
                    "attempts": attempts,
                    "error": error,
                    "updatedAt": moment,
                },
            )
            return STAGE_UNVERIFIED, error
        await _firebase_patch(
            db, path, {"attempts": attempts, "error": error, "updatedAt": moment}
        )
        return state, error

    async def _stage_clean(self, db, schedule_id):
        """الخطوة 4: حذف الملف من R2 ثم كل معلومات الريل من Firebase.

        لا حذف قبل تأكيد ثانٍ من إنستغرام، ولا مسح لسجلات Firebase قبل التأكد
        أن الملف غاب فعلاً من R2: هكذا لا يضيع فيديو نُشر أو ما زال يُنشر.
        """
        path = f"schedules/{schedule_id}.json"
        current, etag = await _firebase_read(db, path)
        if not isinstance(current, dict):
            return STAGE_PUBLISHED, None

        state = _stage_of(current)
        if state != STAGE_CLEANING:
            return state, None

        media_id = _clean(current.get("mediaId"))
        token = _ig_token(self.env)
        now = int(time.time() * 1000)

        # 4أ) مهلة 5 ثوانٍ بعد النشر لتستقر الحاوية عند Meta.
        target = _int_of(current, "cleanupAt") or (
            _int_of(current, "publishedAt") + CLEANUP_DELAY_MS
        )
        remaining = target - now
        if remaining > 0:
            await asyncio.sleep(min(CLEANUP_DELAY_MS, remaining) / 1000)

        key = self._key(schedule_id)

        # 4ب) تأكيد أخير قبل الحذف (لأن الحذف لا رجعة فيه).
        if not media_id or not token:
            error = "cannot confirm the published reel before deleting its file"
            await _firebase_patch(
                db,
                path,
                {"state": STAGE_UNVERIFIED, "error": error, "updatedAt": int(time.time() * 1000)},
            )
            return STAGE_UNVERIFIED, error

        confirmed = await _ig_media_exists(_ig_user(self.env), token, media_id)
        moment = int(time.time() * 1000)
        if confirmed is not True:
            attempts = _int_of(current, "attempts") + 1
            error = "instagram did not confirm the published reel; the file was kept"
            if attempts >= CLEAN_RETRY_LIMIT:
                await _firebase_patch(
                    db,
                    path,
                    {
                        "state": STAGE_UNVERIFIED,
                        "attempts": attempts,
                        "error": error,
                        "updatedAt": moment,
                    },
                )
                return STAGE_UNVERIFIED, error
            await _firebase_patch(
                db, path, {"attempts": attempts, "error": error, "updatedAt": moment}
            )
            return state, error

        # 4ج) حذف الملف من R2.
        try:
            await self.env.FILES.delete(key)
        except Exception as exc:  # noqa: BLE001 - نعيد المحاولة في التشغيل القادم
            return await self._clean_failed(
                db, path, current, "could not delete the archived file: %s" % _describe(exc)[:200]
            )

        if await _object_exists(self.env, key) is True:
            return await self._clean_failed(
                db, path, current, "the archived file is still in R2 after delete"
            )

        # 4د) بعد التأكد من الحذف فقط: تُمسح معلومات الريل كاملة من Firebase.
        if not await self._forget_reel(db, schedule_id):
            return STAGE_CLEANING, "the file is gone but the database records are still there"
        return STAGE_PUBLISHED, None

    async def _clean_failed(self, db, path, current, error):
        """تسجيل فشل خطوة الحذف: محاولات محدودة ثم تُترك الحالة للمراجعة."""
        attempts = _int_of(current, "attempts") + 1
        moment = int(time.time() * 1000)
        if attempts >= CLEAN_RETRY_LIMIT:
            await _firebase_patch(
                db,
                path,
                {
                    "state": STAGE_PUBLISHED,
                    "attempts": attempts,
                    "error": error,
                    "cleanupError": error,
                    "updatedAt": moment,
                },
            )
            return STAGE_PUBLISHED, error
        await _firebase_patch(
            db, path, {"attempts": attempts, "error": error, "updatedAt": moment}
        )
        return STAGE_CLEANING, error

    async def _forget_reel(self, db, reel_id):
        """يحذف سجل الريل كاملاً (عنوانه وروابطه وغلافه) وموعده من Firebase."""
        clean_id = _safe_id(reel_id)
        if not clean_id:
            return False
        ok = True
        for path in (f"reels/{clean_id}.json", f"schedules/{clean_id}.json"):
            try:
                status = await _firebase_delete(db, path)
            except Exception as exc:  # noqa: BLE001
                ok = False
                print(f"pigeon cleanup {path} failed: {exc}")
                continue
            if status not in (200, 204):
                ok = False
                print(f"pigeon cleanup {path} -> HTTP {status}")
        return ok

    async def _delete_reel(self, reel_id):
        """حذف ريل مجدول من التطبيق: ملف R2 أولاً ثم سجلات Firebase.

        يُرفض أثناء خطوة نشر جارية حتى لا يُحذف الفيديو من تحت إنستغرام.
        """
        reel_id = _safe_id(reel_id)
        if not reel_id:
            return _error("bad id", 404)

        db = self._db()
        schedule = await _firebase_get(db, f"schedules/{reel_id}.json")
        if isinstance(schedule, dict):
            state = _stage_of(schedule)
            if state in (STAGE_PUBLISHING, STAGE_VERIFYING, STAGE_CLEANING):
                return _json(
                    {
                        "ok": False,
                        "error": "the reel is being published right now",
                        "state": state,
                    },
                    status=409,
                )

        key = self._key(reel_id)
        try:
            await self.env.FILES.delete(key)
        except Exception as exc:  # noqa: BLE001
            return _json(
                {
                    "ok": False,
                    "error": "could not delete the archived file: %s"
                    % _describe(exc)[:200],
                },
                status=503,
            )

        if await _object_exists(self.env, key) is True:
            return _json(
                {"ok": False, "error": "the archived file is still in R2"},
                status=503,
            )

        if not await self._forget_reel(db, reel_id):
            return _json(
                {
                    "ok": False,
                    "error": "the file is gone but the database records could not be removed",
                },
                status=503,
            )

        return _json({"ok": True, "id": reel_id, "deleted": True})

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
                # سجل "ready" نهائي: محاولة أرشفة فاشلة بعده لا ترجعه إلى failed
                # ولا تمسح رابطه وحجمه (وإلا ظهر الريل بلا بيانات في التطبيق).
                if state != "ready":
                    existing = await _firebase_get(db, state_path)
                    if (
                        isinstance(existing, dict)
                        and _clean(existing.get("state")) == "ready"
                    ):
                        return
                await _firebase_patch(db, state_path, payload)
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

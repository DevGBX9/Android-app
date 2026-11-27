"""Pigeon Reels API — Cloudflare Python Worker.

المهمة: يستقبل رابط ريل إنستغرام أو فيديو تيك توك، ينزّل الفيديو، يخزنه في
R2، ويحدّث حالة المعالجة والتقدّم في Firebase Realtime Database تحت
`/reels/<id>/archive`، ثم يقدّم الملف للتحميل من `GET /download/<id>`.

كذلك ينشر الريلز المجدولة من `/schedules/<id>` إلى إنستغرام عند حلول موعدها
(19:00 بتوقيت الجزائر) عبر Instagram Graph API، من cron داخل الـWorker، لا من
جدولة إنستغرام نفسها. الملف العام الذي يقرأه Meta من `GET /media/<id>`.

البنية (أربع طبقات في هذا الملف، بالترتيب):
    1) أدوات التنزيل والاستخراج وGraph API (دوال مساعدة نقية).
    2) الأخطاء والعقد الموحّد والمخزن `Store`: كل قراءة/كتابة Firebase تمر منه،
       وكل تغيير حالة كتابة مشروطة بـETag (لا كتابة عشوائية على سجل يتغير).
    3) محرّك النشر `Engine`: سلسلة واحدة، وخطوة واحدة لكل نداء، وحجز قبل كل
       نداء خارجي. ثم `Commands`: أوامر idempotent بقواعد صلاحية صريحة.
    4) الراوتر `Default`: مصادقة واحدة، ورد JSON موحّد، ولا منطق نشر فيه.

المسارات:
    GET    /                  معلومات الخدمة
    GET    /health            فحص سريع
    GET    /state             لقطة الحالة الكاملة (المصدر الوحيد للتطبيق)
    GET    /schedules         اسم قديم لنفس اللقطة (توافق خلفي)
    POST   /schedules         {"id", "at"} -> يكتب الموعد ويزيح المتداخل
    POST   /schedules/order   {"ids": [..]} -> يوزّع المواعيد حسب ترتيب الشبكة
    POST   /publish-now       {"id"} -> ينشر الآن ثم ينظّف (idempotent)
    GET|POST /publish         تشغيل المجدول فوراً وتقرير كامل (احتياط للـcron)
    POST   /reels             {"id", "url", "thumbnail"?, "media"?} -> أرشفة
    GET    /reels/<id>        حالة ريل واحد (أرشيف + الأوامر المسموحة)
    DELETE /reels/<id>        حذف الملف والسجلات (409 أثناء النشر)
    GET    /download/<id>     ملف الفيديو من R2 للتحميل على الجهاز
    GET    /media/<id>        نفس الملف للعرض العام (لـMeta، بلا مفتاح)

كل رد JSON: {"ok", "service", "version", "serverTime", "requestId"} ثم حقول
النتيجة، أو {"ok": false, "error", "errorCode"} عند الفشل. الأوامر المتكررة
تعيد النتيجة نفسها ولا تكرر أثرها.

كل الطلبات تُرفض بلا `X-Api-Key` مطابق إن كان `API_TOKEN` مضبوطاً، ما عدا
`/media/<id>` و`/health` و`/`.
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
    try:
        return json.loads(text)
    except Exception:  # noqa: BLE001 - رد غير JSON (صفحة HTML مثلاً)
        return None


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

# ==========================================================================
# الطبقة 1 — العقد الموحّد: الأخطاء، الثوابت، التحقق، والمخزن
# ==========================================================================
# كل مسار في هذا الملف يتبع نفس القواعد:
#   1) أي طلب يُقرأ ويُتحقق منه قبل أي كتابة (لا كتابة على مدخل ناقص).
#   2) أي تغيير حالة يمر عبر Store.transition/commit = كتابة مشروطة بـETag.
#   3) أي فشل يرفع ApiError، والراوتر وحده يحوّله إلى رد JSON موحّد.
#   4) الأوامر idempotent: تكرار الأمر لا يكرر أثره (لا نشر مزدوج، لا حذف أعمى).

CAS_ATTEMPTS = 4
SERVICE_VERSION = 14

# الحالات النهائية: لا تُعاد تلقائياً أبداً.
FINAL_STAGES = (STAGE_PUBLISHED, STAGE_FAILED, STAGE_UNVERIFIED)
# الحالات التي يجري فيها عمل خارجي الآن: لا تعديل ولا حذف ولا نشر ثانٍ.
IN_FLIGHT_STAGES = (STAGE_PUBLISHING, STAGE_VERIFYING, STAGE_CLEANING)

# نطاق المواعيد المقبول من التطبيق.
MAX_FUTURE_MS = 366 * 24 * 60 * 60 * 1000
MIN_LEAD_MS = 30_000

# قفل تعاوني بين cron وترتيب الشبكة: احتياط تنظيمي فوق حجز كل سجل بـETag.
MUTEX_PATH = "worker/mutex.json"
MUTEX_LEASE_MS = 120_000
# مؤشر عرض فقط: يتغيّر مع كل أمر حتى تعرف الواجهة أن اللقطة تغيّرت.
REVISION_PATH = "worker/revision.json"
# قناة الأوامر: التطبيق يكتب أمراً هنا، والـcron ينفّذه ويكتب نتيجته.
# هذا يجعل كل الأوامر تعمل حتى عندما تحجب شبكة الهاتف نطاق الـWorker.
COMMANDS_PATH = "commands"
COMMAND_MAX_PER_RUN = 6
COMMAND_TTL_MS = 30 * 60 * 1000


class ApiError(Exception):
    """خطأ واحد موحّد لكل مسارات الفشل، يحمل رمزاً ورسالة وحالة HTTP."""

    def __init__(self, code, message, status=400):
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status


def _now():
    return int(time.time() * 1000)


def _new_request_id():
    from uuid import uuid4

    return uuid4().hex[:16]


def _reply(payload=None, status=200, request_id=None):
    """رد ناجح موحّد. الحقول القديمة تبقى في الجذر لأن التطبيق يقرأها."""
    body = {
        "ok": True,
        "service": "pigeon-reels",
        "version": SERVICE_VERSION,
        "serverTime": _now(),
    }
    if request_id:
        body["requestId"] = request_id
    if payload:
        body.update(payload)
    return _json(body, status)


def _fail(code, message, status=400, request_id=None):
    body = {
        "ok": False,
        "service": "pigeon-reels",
        "version": SERVICE_VERSION,
        "serverTime": _now(),
        "error": _clean(message, "request failed"),
        "errorCode": code,
    }
    if request_id:
        body["requestId"] = request_id
    return _json(body, status)


# --------------------------------------------------------------------------
# التحقق من المدخلات: كل شيء يُرفض برمز واضح قبل أي كتابة
# --------------------------------------------------------------------------


def _require_id(value):
    clean = _safe_id(value)
    if not clean:
        raise ApiError("invalid_request", "missing or invalid id")
    return clean


def _require_at(value):
    try:
        at = int(value or 0)
    except (TypeError, ValueError):
        raise ApiError("invalid_request", "at must be epoch milliseconds")
    now = _now()
    if at <= 0:
        raise ApiError("invalid_request", "missing at")
    if at < now - 60_000:
        raise ApiError("invalid_request", "pick a time in the future")
    if at > now + MAX_FUTURE_MS:
        raise ApiError("invalid_request", "the date is too far in the future")
    return at


def _require_ids(value, minimum=2):
    if not isinstance(value, list):
        raise ApiError("invalid_request", "missing ids")
    ids = []
    for raw in value:
        clean = _safe_id(raw)
        if not clean:
            raise ApiError("invalid_request", "invalid id in ids")
        if clean in ids:
            raise ApiError("invalid_request", "duplicate id in ids")
        ids.append(clean)
    if len(ids) < minimum:
        raise ApiError("invalid_request", "at least two reels are needed")
    return ids


async def _body_of(request):
    try:
        body = await request.json()
    except Exception:  # noqa: BLE001 - جسم غير JSON
        raise ApiError("invalid_body", "invalid json body")
    if not isinstance(body, dict):
        raise ApiError("invalid_body", "json body must be an object")
    return body


def _snap_to_minute(at, now):
    """الموعد على بداية دقيقة كاملة، ولا يقل عن مهلة قصيرة من الآن."""
    settled = at - (at % 60_000)
    if settled <= now + MIN_LEAD_MS:
        settled += 60_000
    return settled


# --------------------------------------------------------------------------
# المخزن: المسار الوحيد للكتابة في Firebase
# --------------------------------------------------------------------------


def _node_path(path):
    """مسار Firebase REST دائماً ينتهي بـ`.json`: بدونه يرجع الموقع صفحة HTML."""
    clean = _clean(path).strip("/")
    if not clean:
        return ".json"
    return clean if clean.endswith(".json") else clean + ".json"


class Store:
    """يغلّف Firebase: قراءة، كتابة مشروطة، وحذف. لا أحد يكتب خارجه."""

    def __init__(self, db):
        self.db = db

    async def get(self, path):
        return await _firebase_get(self.db, _node_path(path))

    async def read(self, path):
        """(السجل، ETag) — أساس كل حجز آمن."""
        return await _firebase_read(self.db, _node_path(path))

    async def put(self, path, payload):
        status = await _firebase_put(self.db, _node_path(path), payload)
        if status not in (200, 204):
            raise ApiError("store_failed", "firebase write failed (HTTP %s)" % status, 502)
        return payload

    async def patch(self, path, payload):
        status = await _firebase_patch(self.db, _node_path(path), payload)
        if status not in (200, 204):
            raise ApiError("store_failed", "firebase write failed (HTTP %s)" % status, 502)
        return payload

    async def delete(self, path):
        return await _firebase_delete(self.db, _node_path(path))

    async def commit(self, path, etag, current, patch):
        """كتابة مشروطة مع إعادة محاولة واحدة عند تغيّر السجل.

        إن لم يأتِ ETag (سجل جديد) نستخدم PATCH (دمج) لأنه لا يمكن أن يمسح
        حقولاً لم نرسلها. وجود ETag يعني PUT مشروطاً: إما ننجح أو 412.
        """
        merged = dict(current) if isinstance(current, dict) else {}
        merged.update(patch)
        node = _node_path(path)
        for _attempt in range(CAS_ATTEMPTS):
            if etag:
                status = await _firebase_compare_write(
                    self.db, node, etag, current or {}, patch
                )
            else:
                status = await _firebase_patch(self.db, node, patch)
            if status in (200, 204):
                return merged
            if status not in (400, 409, 412):
                raise ApiError(
                    "store_failed", "firebase write failed (HTTP %s)" % status, 502
                )
            current, etag = await self.read(path)
            if not isinstance(current, dict):
                current, etag = {}, None
            merged = dict(current)
            merged.update(patch)
        raise ApiError("conflict", "the record kept changing; try again", 409)

    async def transition(self, path, expected, to_state, extra=None):
        """انتقال حالة واحد: ينجح فقط إن كانت الحالة ما زالت [expected].

        يرجّع السجل بعد الكتابة، أو السجل الحالي كما هو إن لم تكن الحالة
        متوقعة (أي أن تشغيلاً آخر سبقنا)، أو None إن تعذّر الحجز.
        """
        for _attempt in range(CAS_ATTEMPTS):
            current, etag = await self.read(path)
            if not isinstance(current, dict):
                return None
            if expected is not None and _stage_of(current) not in expected:
                # سبقنا تشغيل آخر إلى الحالة: لا حجز ولا كتابة.
                return None
            moment = _now()
            patch = {"state": to_state, "stageAt": moment, "updatedAt": moment}
            if extra:
                patch.update(extra)
            try:
                return await self.commit(path, etag, current, patch)
            except ApiError as exc:
                if exc.code != "conflict":
                    raise
        return None

    async def bump_revision(self):
        """مؤشر عرض فقط (للمزامنة في الواجهة): لا يُفشل أي عملية."""
        try:
            current = await self.get(REVISION_PATH)
            rev = _int_of(current, "rev") if isinstance(current, dict) else 0
            await self.patch(REVISION_PATH, {"rev": rev + 1, "at": _now()})
        except Exception:  # noqa: BLE001 - تجميلي
            return


# --------------------------------------------------------------------------
# صورة الريل: المكان الوحيد الذي يُشتق منه ما يعرضه التطبيق وما يحق له
# --------------------------------------------------------------------------


def _archive_view(archive):
    record = archive if isinstance(archive, dict) else {}
    state = _clean(record.get("state"), "unknown") or "unknown"
    return {
        "state": state,
        "progress": max(0, min(100, _int_of(record, "progress"))),
        "bytes": _int_of(record, "bytes"),
        "ready": state == "ready",
        "error": _clean(record.get("error")) or None,
    }


def _actions_for(state, archive_state):
    """الأوامر المسموحة الآن — يقررها الخادم من الحالة وحدها، لا التطبيق."""
    if state in (STAGE_PUBLISHED, STAGE_PUBLISHING, STAGE_VERIFYING, STAGE_CLEANING):
        return []
    if state == STAGE_UNVERIFIED:
        # نُشر؟ غير مؤكد: لا نشر ولا إعادة جدولة، فقط الحذف (بيد المالك).
        return ["delete"]
    actions = ["publish_now", "reschedule", "delete"]
    if archive_state == "ready":
        actions.insert(0, "download")
    return actions


def _reel_view(record, archive, now, order=0):
    at = _int_of(record, "at")
    state = _stage_of(record)
    archive_view = _archive_view(archive)
    actions = _actions_for(state, archive_view["state"])
    return {
        "at": at,
        "state": state,
        "order": order,
        "remainingMs": max(0, at - now) if at > 0 else 0,
        "error": _clean(record.get("error")) or None,
        "publishedAt": _int_of(record, "publishedAt"),
        "mediaId": _clean(record.get("mediaId")) or None,
        "archive": archive_view,
        "allowedActions": actions,
        "downloadable": "download" in actions,
        "publishable": "publish_now" in actions,
        "reschedulable": "reschedule" in actions,
        "deletable": "delete" in actions,
    }


# --------------------------------------------------------------------------
# قفل تعاوني بين cron وترتيب الشبكة
# --------------------------------------------------------------------------


async def _acquire_mutex(store, owner, lease_ms=MUTEX_LEASE_MS):
    try:
        current = await store.get(MUTEX_PATH)
    except ApiError:
        current = None
    now = _now()
    if isinstance(current, dict):
        held = _clean(current.get("id"))
        held_at = _int_of(current, "at")
        if held and held != owner and now - held_at < lease_ms:
            return False
    try:
        await store.patch(MUTEX_PATH, {"id": owner, "at": now})
    except ApiError:
        return False
    return True


async def _release_mutex(store, owner):
    try:
        current = await store.get(MUTEX_PATH)
        if isinstance(current, dict) and _clean(current.get("id")) == owner:
            await store.delete(MUTEX_PATH)
    except Exception:  # noqa: BLE001 - القفل تحسين لا شرط
        return
# ==========================================================================
# الطبقة 2 — محرّك النشر: سلسلة واحدة تُقرأ من السجل وتُحجز قبل كل نداء خارجي
# ==========================================================================
# المحرّك لا يعرف HTTP ولا التطبيق. مداخله: معرّف الريل وسجل Firebase، ومخرجاته:
# (الحالة، رسالة الخطأ). كل كتابة حالة تمر بStore.transition، فتشغيلان في نفس
# اللحظة لا ينشران الريل مرتين ولا يحذفان ملفاً تحت إنستغرام.


class Engine:
    def __init__(self, env, store):
        self.env = env
        self.store = store

    def key(self, reel_id):
        return f"reels/{reel_id}.mp4"

    async def exists(self, reel_id):
        return await _object_exists(self.env, self.key(reel_id))

    # ------------------------------------------------------------------
    # الأرشفة: تنزيل الفيديو إلى R2 مع تقدّم، ثم حالة ready نهائية
    # ------------------------------------------------------------------

    async def archive(self, reel_id, code, tiktok, source, origin, thumbnail, app_media=None):
        state_path = f"reels/{reel_id}/archive.json"
        started = _now()
        deadline = time.monotonic() + ARCHIVE_BUDGET_S

        def remaining():
            return deadline - time.monotonic()

        async def report(state, progress, **extra):
            payload = {"state": state, "progress": int(progress), "updatedAt": _now()}
            payload.update(extra)
            try:
                # الحالة ready نهائية: محاولة فاشلة بعدها لا ترجعها إلى failed
                # ولا تمسح رابط الملف وحجمه.
                if state != "ready":
                    existing = await self.store.get(state_path)
                    if (
                        isinstance(existing, dict)
                        and _clean(existing.get("state")) == "ready"
                    ):
                        return
                await self.store.patch(state_path, payload)
            except Exception:  # noqa: BLE001 - تقدّم فقط، ولا يُفشل التنزيل
                pass

        try:
            await report("downloading", 3)

            info = {}
            attempts = []
            hosts = []
            last_error = "no media url"

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
                            _resolve_tiktok(
                                tiktok, deadline=time.monotonic() + budget
                            ),
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
                    attempts.append("%s -> no answer in %.0fs" % (source, budget))
                    return []

            await report("downloading", 6)

            # المسار السريع: رابط CDN النظيف الذي حلّه الهاتف.
            data = None
            if app_media:
                data = await attempt(
                    [
                        {
                            "url": app_media,
                            "bytes": 0,
                            "headers": {
                                "user-agent": BROWSER_UA,
                                "accept": "*/*",
                            },
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
            await self.env.FILES.put(self.key(reel_id), data)

            await report(
                "ready",
                100,
                url="%s/download/%s" % (origin, reel_id),
                bytes=len(data),
                source=source,
                title=info.get("title") or None,
                thumbnail=thumbnail or info.get("thumbnail") or None,
                videoDurationMs=info.get("durationMs") or None,
                durationMs=_now() - started,
            )
            return True
        except Exception as exc:  # noqa: BLE001 - نسجّل السبب ونعيد false
            attempts = 0
            try:
                existing = await self.store.get(state_path)
                if isinstance(existing, dict):
                    attempts = _int_of(existing, "attempts")
            except Exception:  # noqa: BLE001 - العدّاد تحسين للتباعد
                pass
            await report(
                "failed",
                0,
                error=("%s: %s" % (exc.__class__.__name__, exc))[:300],
                attempts=attempts + 1,
            )
            return False

    async def archive_now(self, reel_id):
        """يؤرشف فوراً إن لم يكن الملف في R2. يرجّع True فقط إذا صار موجوداً."""
        if await self.exists(reel_id) is True:
            return True

        reel = await self.store.get(f"reels/{reel_id}.json") or {}
        if not isinstance(reel, dict):
            return False

        link = _clean(reel.get("url"))
        code = _instagram_code(link)
        tiktok = None if code else _tiktok_link(link)
        if not code and not tiktok:
            return False

        await self.archive(
            reel_id=reel_id,
            code=code,
            tiktok=tiktok,
            source="instagram" if code else "tiktok",
            origin=_ig_public_base(self.env),
            thumbnail=_clean(reel.get("thumbnail")) or None,
            app_media=_safe_media_url(reel.get("media")),
        )
        return await self.exists(reel_id) is True

    async def archive_pending(self, limit=2):
        """يؤرشف أي ريل مسجّل في Firebase ما زال بلا ملف في R2.

        هذا هو الضمان الذي لا يعتمد على الهاتف: الرابط محفوظ في السجل، فالـcron
        يكمّل الأرشفة وحدَه حتى لو مات طلب التطبيق في منتصفه. المحاولات
        الفاشلة تتباعد (30s, 2m, 5m, 15m, ...) حتى لا تلتهم الدورة.
        """
        reels = await self.store.get("reels") or {}
        if not isinstance(reels, dict):
            return 0

        backoff = (30_000, 120_000, 300_000, 900_000, 1_800_000, 3_600_000)
        now = _now()
        started = 0

        for raw_id, record in reels.items():
            if started >= limit:
                break
            clean_id = _safe_id(raw_id)
            if not clean_id or not isinstance(record, dict):
                continue

            archive = record.get("archive")
            archive = archive if isinstance(archive, dict) else {}
            state = _clean(archive.get("state"))
            if state == "ready":
                continue

            link = _clean(record.get("url"))
            if not link:
                continue

            updated = _int_of(archive, "updatedAt")
            if state in ("queued", "downloading", "storing"):
                if now - updated < STALE_JOB_MS:
                    continue  # مهمة تعمل الآن
            elif state == "failed":
                attempts = _int_of(archive, "attempts")
                if attempts >= len(backoff):
                    continue  # رابط ميت: لا نُعيد المحاولة بلا نهاية
                if now - updated < backoff[min(attempts, len(backoff) - 1)]:
                    continue

            if await self.exists(clean_id) is True:
                continue  # الملف موجود: تبقى فقط كتابة الحالة

            code = _instagram_code(link)
            tiktok = None if code else _tiktok_link(link)
            if not code and not tiktok:
                continue

            await self.archive(
                reel_id=clean_id,
                code=code,
                tiktok=tiktok,
                source="instagram" if code else "tiktok",
                origin=_ig_public_base(self.env),
                thumbnail=_clean(record.get("thumbnail")) or None,
                app_media=_safe_media_url(record.get("media")),
            )
            started += 1

        return started

    async def archive_missing(self):
        """يعيد أرشفة موعد قادم ما زال ملفه غائباً عن R2 (حماية من فقدان الأرشفة)."""
        schedules = await self.store.get("schedules") or {}
        if not isinstance(schedules, dict):
            return 0

        candidates = []
        for schedule_id, schedule in schedules.items():
            if not isinstance(schedule, dict):
                continue
            at = _int_of(schedule, "at")
            if at <= 0:
                continue
            if _stage_of(schedule) not in (
                STAGE_SCHEDULED,
                STAGE_PREPARING,
                STAGE_PREPARED,
            ):
                continue
            if _clean(schedule.get("containerId")):
                continue  # الحاوية موجودة: الملف وصل R2 سابقاً
            clean_id = _safe_id(schedule_id)
            if clean_id:
                candidates.append((at, clean_id))

        candidates.sort()
        started = 0
        for _at, clean_id in candidates[:1]:
            if await self.exists(clean_id) is True:
                continue
            if await self.archive_now(clean_id):
                started += 1
        return started

    # ------------------------------------------------------------------
    # الحاوية: تُنشأ قبل الموعد حتى لا يتأخر النشر
    # ------------------------------------------------------------------

    async def prepare_container(self, reel_id):
        token = _ig_token(self.env)
        if not token:
            return None, "IG_ACCESS_TOKEN is not configured"

        media_url = f"{_ig_public_base(self.env)}/media/{reel_id}"
        try:
            container_id = await _ig_create_container(
                _ig_user(self.env), token, media_url, ""
            )
        except Exception as exc:  # noqa: BLE001 - المتصل يعيد المحاولة
            return None, _describe(exc)[:300]
        return container_id, None

    # ------------------------------------------------------------------
    # التقدّم: خطوة واحدة في كل نداء
    # ------------------------------------------------------------------

    async def advance(self, reel_id, record=None):
        path = f"schedules/{reel_id}.json"
        if not isinstance(record, dict):
            record, _etag = await self.store.read(path)
        if not isinstance(record, dict):
            return STAGE_SCHEDULED, "the schedule record disappeared"

        now = _now()
        state = _stage_of(record)
        stage_at = _int_of(record, "stageAt")
        if state in IN_FLIGHT_STAGES or state == STAGE_PREPARING:
            # الخطوة محجوزة لتشغيل آخر لم ينته بعد.
            if stage_at > 0 and now - stage_at < STAGE_LEASE_MS:
                return state, None

        if state in (STAGE_SCHEDULED, STAGE_PREPARING):
            at = _int_of(record, "at") if state == STAGE_SCHEDULED else 0
            if state == STAGE_SCHEDULED and at <= 0:
                return state, None
            if state == STAGE_SCHEDULED and at > now and at - now > PREPARE_LEAD_MS:
                return state, None
            return await self._prepare(reel_id)

        if state == STAGE_PREPARED:
            if _int_of(record, "at") > now:
                return state, None  # الحاوية جاهزة، ننتظر الموعد
            return await self._publish(reel_id)

        if state == STAGE_PUBLISHING:
            return await self._publish(reel_id)
        if state == STAGE_VERIFYING:
            return await self._verify(reel_id)
        if state == STAGE_CLEANING:
            return await self._clean(reel_id)
        return state, None

    async def _prepare(self, reel_id):
        path = f"schedules/{reel_id}.json"
        current, _etag = await self.store.read(path)
        if not isinstance(current, dict):
            return STAGE_SCHEDULED, "the schedule record disappeared"

        state = _stage_of(current)
        if state not in (STAGE_SCHEDULED, STAGE_PREPARING):
            return state, None

        # حاوية موجودة من محاولة سابقة: لا ننشئ ثانية، نعتمدها.
        if _clean(current.get("containerId")):
            merged = await self.store.transition(
                path,
                (STAGE_SCHEDULED, STAGE_PREPARING),
                STAGE_PREPARED,
                {"error": None},
            )
            return (STAGE_PREPARED, None) if merged else (state, None)

        if await self.exists(reel_id) is not True:
            return STAGE_SCHEDULED, ARCHIVE_WAIT

        claimed = await self.store.transition(
            path,
            (STAGE_SCHEDULED, STAGE_PREPARING),
            STAGE_PREPARING,
            {"error": None},
        )
        if not claimed:
            return state, None

        container_id, error = await self.prepare_container(reel_id)
        if error is None and container_id:
            ready = await self.store.transition(
                path,
                (STAGE_PREPARING,),
                STAGE_PREPARED,
                {
                    "containerId": container_id,
                    "preparedAt": _now(),
                    "attempts": 0,
                    "error": None,
                },
            )
            return (STAGE_PREPARED, None) if ready else (STAGE_PREPARING, None)

        attempts = _int_of(current, "attempts") + 1
        error = error or "instagram did not return a container id"
        target = STAGE_FAILED if attempts >= PREPARE_RETRY_LIMIT else STAGE_SCHEDULED
        merged = await self.store.transition(
            path, (STAGE_PREPARING,), target, {"attempts": attempts, "error": error}
        )
        return (target if merged else STAGE_PREPARING), error

    async def _publish(self, reel_id):
        path = f"schedules/{reel_id}.json"
        current, _etag = await self.store.read(path)
        if not isinstance(current, dict):
            return STAGE_SCHEDULED, "the schedule record disappeared"

        state = _stage_of(current)
        if state not in (STAGE_PREPARED, STAGE_PUBLISHING):
            return state, None

        token = _ig_token(self.env)
        if not token:
            error = "IG_ACCESS_TOKEN is not configured"
            await self.store.transition(
                path, (STAGE_PREPARED, STAGE_PUBLISHING), STAGE_FAILED, {"error": error}
            )
            return STAGE_FAILED, error

        user_id = _ig_user(self.env)
        moment = _now()

        # تشغيل سابق بدأ media_publish ثم انقطع: لا نعيد النشر أبداً، نسأل
        # الحساب أولاً إن كان الريل ظهر فعلاً.
        if state == STAGE_PUBLISHING:
            stage_at = _int_of(current, "stageAt") or moment
            published_id = await _ig_published_media(
                user_id, token, stage_at - 180_000
            )
            if published_id:
                await self._enter_verifying(reel_id, published_id, stage_at, "scan")
                return await self._verify(reel_id)
            if moment - stage_at < PUBLISH_STUCK_MS:
                return state, None
            error = "publishing was interrupted; check Instagram before retrying"
            await self.store.transition(
                path, (STAGE_PUBLISHING,), STAGE_FAILED, {"error": error}
            )
            return STAGE_FAILED, error

        container_id = _clean(current.get("containerId"))
        if not container_id:
            error = "the Instagram container id is missing"
            await self.store.transition(
                path, (STAGE_PREPARED,), STAGE_SCHEDULED, {"error": error}
            )
            return STAGE_SCHEDULED, error

        try:
            status_code, status_text = await _ig_container_status(container_id, token)
        except Exception as exc:  # noqa: BLE001 - نعيد المحاولة في التشغيل القادم
            error = _describe(exc)[:300]
            attempts = _int_of(current, "attempts") + 1
            exhausted = attempts >= SCHEDULE_RETRY_LIMIT
            target = STAGE_FAILED if exhausted else STAGE_PREPARED
            await self.store.transition(
                path, (STAGE_PREPARED,), target, {"attempts": attempts, "error": error}
            )
            return target, error

        if status_code in ("ERROR", "EXPIRED"):
            error = ("instagram container %s: %s" % (status_code.lower(), status_text))[
                :300
            ]
            await self.store.transition(
                path, (STAGE_PREPARED,), STAGE_FAILED, {"error": error}
            )
            return STAGE_FAILED, error

        if status_code != "FINISHED":
            attempts = _int_of(current, "attempts") + 1
            exhausted = attempts >= SCHEDULE_RETRY_LIMIT
            error = "instagram did not finish processing the video" if exhausted else None
            target = STAGE_FAILED if exhausted else STAGE_PREPARED
            await self.store.transition(
                path, (STAGE_PREPARED,), target, {"attempts": attempts, "error": error}
            )
            return target, error

        # ريل واحد ينشر في المرة. النشر اليدوي يتجاوز فاصل الدقيقتين فقط،
        # ولا يتجاوز أبداً التداخل مع ريل ينشر الآن.
        manual = _truthy(current.get("manualPublish"))
        if not await self.publish_slot_free(reel_id, moment, ignore_gap=manual):
            return state, PUBLISH_BUSY

        claimed = await self.store.transition(
            path, (STAGE_PREPARED,), STAGE_PUBLISHING, {"error": None, "attempts": 0}
        )
        if not claimed:
            return state, None

        started = _now()
        await self.lock_publish(reel_id, started)
        media_id = None
        error = None
        try:
            media_id = await _ig_publish(user_id, token, container_id)
        except Exception as exc:  # noqa: BLE001 - قد يكون النشر نجح والرد ضاع
            error = _describe(exc)[:300]

        # رد media_publish الناجح نفسه دليل النشر؛ البحث في الحساب احتياط فقط.
        source = "api"
        if not media_id:
            source = "scan"
            media_id = await _ig_published_media(user_id, token, started - 180_000)

        if media_id:
            await self._enter_verifying(reel_id, media_id, started, source)
            return await self._verify(reel_id)

        attempts = _int_of(current, "attempts") + 1
        error = error or "media_publish returned no id"
        exhausted = attempts >= PUBLISH_RETRY_LIMIT
        target = STAGE_FAILED if exhausted else STAGE_PREPARED
        await self.store.transition(
            path, (STAGE_PUBLISHING,), target, {"attempts": attempts, "error": error}
        )
        return target, error

    async def _enter_verifying(self, reel_id, media_id, published_at, source):
        path = f"schedules/{reel_id}.json"
        moment = _now()
        if published_at <= 0 or published_at > moment:
            published_at = moment
        await self.store.transition(
            path,
            (STAGE_PUBLISHING,),
            STAGE_VERIFYING,
            {
                "mediaId": media_id,
                "publishedAt": published_at,
                "cleanupAt": published_at + CLEANUP_DELAY_MS,
                "publishSource": source,
                "attempts": 0,
                "error": None,
            },
        )

    async def _verify(self, reel_id):
        path = f"schedules/{reel_id}.json"
        current, _etag = await self.store.read(path)
        if not isinstance(current, dict):
            return STAGE_SCHEDULED, "the schedule record disappeared"

        state = _stage_of(current)
        if state != STAGE_VERIFYING:
            return state, None

        media_id = _clean(current.get("mediaId"))
        token = _ig_token(self.env)
        if not media_id or not token:
            error = "cannot confirm the published reel"
            await self.store.transition(
                path, (STAGE_VERIFYING,), STAGE_UNVERIFIED, {"error": error}
            )
            return STAGE_UNVERIFIED, error

        confirmed = await self.published_confirmed(current, media_id, token)
        if confirmed is True:
            published_at = _int_of(current, "publishedAt") or _now()
            cleaned = await self.store.transition(
                path,
                (STAGE_VERIFYING,),
                STAGE_CLEANING,
                {
                    "error": None,
                    "attempts": 0,
                    "cleanupAt": published_at + CLEANUP_DELAY_MS,
                },
            )
            if not cleaned:
                return STAGE_VERIFYING, None
            return await self._clean(reel_id)

        attempts = _int_of(current, "attempts") + 1
        if confirmed is False:
            error = "instagram did not confirm the published reel"
        else:
            error = "could not confirm the published reel with instagram"

        if attempts >= VERIFY_RETRY_LIMIT:
            # بلا تأكيد لا نحذف الملف أبداً: يبقى الريل للمراجعة.
            await self.store.transition(
                path,
                (STAGE_VERIFYING,),
                STAGE_UNVERIFIED,
                {"attempts": attempts, "error": error},
            )
            return STAGE_UNVERIFIED, error

        # نكتب المحاولة بلا تجديد "stageAt": الحجز انتهى بعد نافذته، والتشغيل
        # القادم (كل دقيقة) يعيد الفحص بدل أن يُحجب حجزاً قديماً.
        await self.store.commit(
            path,
            _etag,
            current,
            {"state": STAGE_VERIFYING, "attempts": attempts, "error": error, "updatedAt": _now()},
        )
        return STAGE_VERIFYING, error

    async def published_confirmed(self, schedule, media_id, token):
        """هل النشر مؤكد بما يكفي لحذف الملف من R2؟"""
        if not media_id:
            return False

        if token:
            confirmed = await _ig_media_exists(_ig_user(self.env), token, media_id)
            if confirmed is True:
                return True
            proof = (
                _clean(schedule.get("publishSource")) == "api"
                or _truthy(schedule.get("manualPublish"))
            )
            if confirmed is False and not proof:
                return False

        return _int_of(schedule, "publishedAt") > 0

    async def _clean(self, reel_id):
        """حذف الملف من R2 ثم سجلات Firebase — بعد تأكيد ثانٍ فقط."""
        path = f"schedules/{reel_id}.json"
        current, _etag = await self.store.read(path)
        if not isinstance(current, dict):
            return STAGE_PUBLISHED, None

        state = _stage_of(current)
        if state != STAGE_CLEANING:
            return state, None

        media_id = _clean(current.get("mediaId"))
        token = _ig_token(self.env)
        now = _now()

        target = _int_of(current, "cleanupAt") or (
            _int_of(current, "publishedAt") + CLEANUP_DELAY_MS
        )
        remaining = target - now
        if remaining > 0:
            await asyncio.sleep(min(CLEANUP_DELAY_MS, remaining) / 1000)

        if not media_id or not token:
            error = "cannot confirm the published reel before deleting its file"
            await self.store.transition(
                path, (STAGE_CLEANING,), STAGE_UNVERIFIED, {"error": error}
            )
            return STAGE_UNVERIFIED, error

        confirmed = await self.published_confirmed(current, media_id, token)
        if confirmed is not True:
            attempts = _int_of(current, "attempts") + 1
            error = "instagram did not confirm the published reel; the file was kept"
            if attempts >= CLEAN_RETRY_LIMIT:
                await self.store.transition(
                    path,
                    (STAGE_CLEANING,),
                    STAGE_UNVERIFIED,
                    {"attempts": attempts, "error": error},
                )
                return STAGE_UNVERIFIED, error
            # نفس قاعدة التأكيد: عدّ المحاولة بلا تجديد الحجز.
            await self.store.commit(
                path,
                _etag,
                current,
                {"state": STAGE_CLEANING, "attempts": attempts, "error": error, "updatedAt": _now()},
            )
            return STAGE_CLEANING, error

        key = self.key(reel_id)
        try:
            await self.env.FILES.delete(key)
        except Exception as exc:  # noqa: BLE001 - نعيد المحاولة في تشغيل لاحق
            return await self._clean_failed(
                reel_id,
                "could not delete the archived file: %s" % _describe(exc)[:200],
            )

        if await self.exists(reel_id) is True:
            return await self._clean_failed(
                reel_id, "the archived file is still in R2 after delete"
            )

        if not await self.forget(reel_id):
            return STAGE_CLEANING, "the file is gone but the database records are still there"
        return STAGE_PUBLISHED, None

    async def _clean_failed(self, reel_id, error):
        path = f"schedules/{reel_id}.json"
        current, _etag = await self.store.read(path)
        current = current if isinstance(current, dict) else {}
        attempts = _int_of(current, "attempts") + 1
        if attempts >= CLEAN_RETRY_LIMIT:
            await self.store.transition(
                path,
                (STAGE_CLEANING,),
                STAGE_PUBLISHED,
                {"attempts": attempts, "error": error, "cleanupError": error},
            )
            return STAGE_PUBLISHED, error
        await self.store.transition(
            path, (STAGE_CLEANING,), STAGE_CLEANING, {"attempts": attempts, "error": error}
        )
        return STAGE_CLEANING, error

    async def forget(self, reel_id):
        """يحذف سجل الريل وموعده وأرشيفه من Firebase. True إذا نُظّف كله."""
        clean_id = _safe_id(reel_id)
        if not clean_id:
            return False
        ok = True
        # حذف عقدة الريل يحذف أرشيفها معها (عقدة فرعية).
        for path in (f"reels/{clean_id}.json", f"schedules/{clean_id}.json"):
            try:
                status = await self.store.delete(path)
            except Exception as exc:  # noqa: BLE001
                ok = False
                print("pigeon cleanup %s failed: %s" % (path, exc))
                continue
            if status not in (200, 204):
                ok = False
                print("pigeon cleanup %s -> HTTP %s" % (path, status))
        return ok

    # ------------------------------------------------------------------
    # تنسيق النشر: خانة واحدة وقفل قصير
    # ------------------------------------------------------------------

    async def publish_slot_free(self, reel_id, now, ignore_gap=False):
        lock = await self.store.get(PUBLISH_LOCK_PATH) or {}
        if isinstance(lock, dict) and not ignore_gap:
            busy_id = _safe_id(lock.get("id"))
            started = _int_of(lock, "at")
            if (
                busy_id
                and busy_id != reel_id
                and started > 0
                and now - started < PUBLISH_GAP_MS
            ):
                return False

        schedules = await self.store.get("schedules") or {}
        if not isinstance(schedules, dict):
            return True
        for other_id, other in schedules.items():
            if _safe_id(other_id) == reel_id or not isinstance(other, dict):
                continue
            if _stage_of(other) in IN_FLIGHT_STAGES:
                return False
        return True

    async def lock_publish(self, reel_id, started):
        try:
            await self.store.put(
                PUBLISH_LOCK_PATH, {"id": reel_id, "at": started}
            )
        except Exception as exc:  # noqa: BLE001 - القفل تحسين لا شرط
            print("pigeon publish lock failed: %s" % exc)

    async def advance_until_settled(self, reel_id, budget_s=PUBLISH_NOW_BUDGET_S):
        """يمشي بالسلسلة زمنياً حتى النهاية أو نهاية الميزانية (للنشر اليدوي)."""
        deadline = time.monotonic() + budget_s
        state = STAGE_SCHEDULED
        error = None

        while time.monotonic() < deadline:
            current, _etag = await self.store.read(f"schedules/{reel_id}.json")
            if not isinstance(current, dict):
                if state in (STAGE_VERIFYING, STAGE_CLEANING, STAGE_PUBLISHED):
                    return STAGE_PUBLISHED, None
                return state, "the schedule record disappeared"

            state = _stage_of(current)
            if state in FINAL_STAGES:
                break

            state, error = await self.advance(reel_id, current)
            if state in FINAL_STAGES:
                break
            if error in (ARCHIVE_WAIT, PUBLISH_BUSY):
                break  # لا فائدة من الانتظار: يحتاج ملفاً أو خانة نشر حرّة
            await asyncio.sleep(PUBLISH_NOW_POLL_S)

        return state, error
# ==========================================================================
# الطبقة 3 — الأوامر: التطبيق يرسل أمراً، والخادم يقرر ويحفظ ويعيد الحالة
# ==========================================================================
# كل أمر هنا idempotent: تكراره بنفس الشروط لا يكرر أثره، ويعيد الحالة الحالية
# بدل خطأ. وكل أمر يتحقق من الحالة المسموح بها قبل أي كتابة أو نداء خارجي.


class Commands:
    def __init__(self, env, store, engine):
        self.env = env
        self.store = store
        self.engine = engine

    # ------------------------------------------------------------------
    # archive.start
    # ------------------------------------------------------------------

    async def archive(self, request_id, body):
        reel_id = _require_id(_field(body, "id"))
        link = _clean(_field(body, "url"))
        thumbnail = _clean(_field(body, "thumbnail"))
        app_media = _safe_media_url(_field(body, "media"))
        archive_path = f"reels/{reel_id}/archive.json"

        current = await self.store.get(archive_path)
        current = current if isinstance(current, dict) else {}
        state = _clean(current.get("state"), "unknown") or "unknown"

        # ready نهائية، والمهمة الجارية حديثاً لا تُصفَّر (كانت إعادة الطلب
        # تُرجع التقدّم من 8% إلى 2%).
        if state == "ready":
            return {"id": reel_id, "state": "ready", "archive": _archive_view(current)}, 200
        if (
            state in ("queued", "downloading", "storing")
            and _now() - _int_of(current, "updatedAt") < STALE_JOB_MS
        ):
            return {
                "id": reel_id,
                "state": state,
                "archive": _archive_view(current),
            }, 202

        code = _instagram_code(link)
        tiktok = None if code else _tiktok_link(link)
        if not code and not tiktok:
            raise ApiError(
                "unsupported_link",
                "only instagram reel and tiktok video links are supported",
            )
        source = "instagram" if code else "tiktok"

        # السجل نفسه يُحفظ هنا أيضاً: لو انقطع طلب التطبيق بعد الأرشفة تفشل
        # كتابته، أو احتاج الـcron إعادة المحاولة، فالرابط موجود على الخادم.
        try:
            await self.store.patch(
                f"reels/{reel_id}.json",
                {
                    "url": link,
                    "thumbnail": thumbnail or None,
                    "source": source,
                },
            )
        except ApiError:
            pass

        try:
            await self.store.patch(
                archive_path,
                {
                    "state": "queued",
                    "progress": 0,
                    "updatedAt": _now(),
                    "jobId": request_id,
                },
            )
        except ApiError:
            pass  # الحالة تُكتب داخل التنزيل على أي حال

        # الرابط الذي حلّه الهاتف يُحفظ حتى يستطيع cron إعادة الأرشفة بنفسه.
        if app_media:
            try:
                await self.store.patch(
                    f"reels/{reel_id}.json", {"media": app_media, "source": source}
                )
            except ApiError:
                pass

        # ننتظر الأرشفة داخل الطلب: `waitUntil` تُلغى بعد الاستجابة، وهذا سبب
        # أن الملف كان لا يصل R2 إلا بإعادة الطلب.
        try:
            await asyncio.wait_for(
                self.engine.archive(
                    reel_id=reel_id,
                    code=code,
                    tiktok=tiktok,
                    source=source,
                    origin=_origin_of(str(self.env_base())),
                    thumbnail=thumbnail,
                    app_media=app_media if source == "tiktok" else None,
                ),
                ARCHIVE_BUDGET_S + 8,
            )
        except BaseException:  # noqa: BLE001 - نكمل بالحالة المسجلة
            pass

        final = await self.store.get(archive_path)
        view = _archive_view(final)
        stored = await self.engine.exists(reel_id)
        return {
            "id": reel_id,
            "state": "ready" if stored is True else view["state"],
            "archive": view,
        }, 202

    def env_base(self):
        return _ig_public_base(self.env)

    # ------------------------------------------------------------------
    # schedule.set
    # ------------------------------------------------------------------

    async def set_schedule(self, body):
        reel_id = _require_id(_field(body, "id"))
        at = _require_at(_field(body, "at"))
        timezone = _clean(_field(body, "timezone"), SCHEDULE_TIMEZONE) or SCHEDULE_TIMEZONE
        path = f"schedules/{reel_id}.json"
        now = _now()

        reel = await self.store.get(f"reels/{reel_id}.json")
        if not isinstance(reel, dict):
            raise ApiError("not_found", "this reel is not registered on the server", 404)

        settled = _snap_to_minute(at, now)
        shifted = settled != at

        current, etag = await self.store.read(path)
        current = current if isinstance(current, dict) else {}

        if current:
            state = _stage_of(current)
            if state in IN_FLIGHT_STAGES or state == STAGE_UNVERIFIED:
                raise ApiError("in_progress", "this reel is being published right now", 409)
            if state == STAGE_PUBLISHED or _int_of(current, "publishedAt") > 0:
                raise ApiError("already_published", "this reel was already published", 409)
            # نفس الموعد ونفس الحالة = الأمر مكرر، نعيد النتيجة بلا كتابة.
            if _int_of(current, "at") == settled and state == STAGE_SCHEDULED:
                return {
                    "id": reel_id,
                    "at": settled,
                    "state": STAGE_SCHEDULED,
                    "timezone": timezone,
                    "remainingMs": max(0, settled - now),
                    "shifted": False,
                    "idempotent": True,
                }, 200

        schedules = await self.store.get("schedules") or {}
        if not isinstance(schedules, dict):
            schedules = {}

        # المواعيد المشغولة: كل ريل آخر ما زال في الطريق إلى النشر.
        taken = []
        for other_id, other in schedules.items():
            clean_id = _safe_id(other_id)
            if not clean_id or clean_id == reel_id or not isinstance(other, dict):
                continue
            if _stage_of(other) in FINAL_STAGES:
                continue
            other_at = _int_of(other, "at")
            if other_at > 0:
                taken.append(other_at)
        taken.sort()

        for _round in range(len(taken) + 1):
            moved = False
            for other_at in taken:
                if abs(settled - other_at) < MIN_SLOT_GAP_MS:
                    settled = other_at + MIN_SLOT_GAP_MS
                    moved = True
            if not moved:
                break
        shifted = shifted or settled != at

        payload = {
            "at": settled,
            "timezone": timezone,
            "state": STAGE_SCHEDULED,
            "attempts": 0,
            "error": None,
            "containerId": None,
            "stageAt": 0,
            "scheduledAt": _int_of(current, "scheduledAt") or now,
            "updatedAt": now,
        }
        await self.store.commit(path, etag, current, payload)
        await self.store.bump_revision()

        return {
            "id": reel_id,
            "at": settled,
            "state": STAGE_SCHEDULED,
            "timezone": timezone,
            "remainingMs": max(0, settled - now),
            "shifted": shifted,
            "idempotent": False,
        }, 200

    # ------------------------------------------------------------------
    # schedule.reorder
    # ------------------------------------------------------------------

    async def reorder(self, request_id, body):
        ids = _require_ids(_field(body, "ids"))
        now = _now()
        owner = "order:" + request_id

        if not await _acquire_mutex(self.store, owner):
            raise ApiError("busy", "another operation is running; try again", 409)

        try:
            schedules = await self.store.get("schedules") or {}
            if not isinstance(schedules, dict):
                raise ApiError("store_failed", "the /schedules node is not an object", 502)

            entries = {}
            times = []
            for clean_id in ids:
                entry = schedules.get(clean_id)
                if not isinstance(entry, dict):
                    raise ApiError("not_found", "this reel is not scheduled on the server", 404)
                state = _stage_of(entry)
                if state in IN_FLIGHT_STAGES or state == STAGE_UNVERIFIED:
                    raise ApiError("in_progress", "a reel is being published right now", 409)
                if state in (STAGE_PUBLISHED, STAGE_FAILED):
                    raise ApiError("invalid_state", "a reel is no longer scheduled", 409)
                at = _int_of(entry, "at")
                if at <= now + MIN_LEAD_MS:
                    raise ApiError(
                        "too_late", "a reel is about to publish; reorder it after", 409
                    )
                entries[clean_id] = entry
                times.append(at)
            times.sort()

            # مواعيد الريلز غير المشمولة بالسحب محجوزة: لا نزيحها صامتين.
            for other_id, other in schedules.items():
                clean_id = _safe_id(other_id)
                if not clean_id or clean_id in entries or not isinstance(other, dict):
                    continue
                if _stage_of(other) in FINAL_STAGES:
                    continue
                other_at = _int_of(other, "at")
                if other_at <= 0:
                    continue
                for at in times:
                    if abs(at - other_at) < MIN_SLOT_GAP_MS:
                        raise ApiError(
                            "conflict",
                            "the new order collides with another scheduled reel",
                            409,
                        )

            result = {}
            for index, clean_id in enumerate(ids):
                entry = entries[clean_id]
                merged = await self.store.transition(
                    f"schedules/{clean_id}.json",
                    (STAGE_SCHEDULED, STAGE_PREPARING, STAGE_PREPARED),
                    STAGE_SCHEDULED,
                    {
                        "at": times[index],
                        "timezone": _clean(entry.get("timezone"), SCHEDULE_TIMEZONE),
                        "attempts": 0,
                        "error": None,
                        "containerId": None,
                        "stageAt": 0,
                        "scheduledAt": _int_of(entry, "scheduledAt") or now,
                    },
                )
                if not merged:
                    # تغيّر السجل بين التحقق والكتابة: لا نصفّ ترتيباً ناقصاً.
                    raise ApiError("conflict", "the order changed while saving; try again", 409)
                result[clean_id] = times[index]

            await self.store.bump_revision()
            moved = sum(
                1
                for index, clean_id in enumerate(ids)
                if times[index] != _int_of(entries[clean_id], "at")
            )
            return {"moved": moved, "times": result, "ids": ids}, 200
        finally:
            await _release_mutex(self.store, owner)

    # ------------------------------------------------------------------
    # publish.now
    # ------------------------------------------------------------------

    async def publish_now(self, reel_id):
        path = f"schedules/{reel_id}.json"
        current, _etag = await self.store.read(path)
        if not isinstance(current, dict):
            raise ApiError("not_found", "this reel has no schedule on the server", 404)

        state = _stage_of(current)
        if state == STAGE_PUBLISHED or _int_of(current, "publishedAt") > 0:
            # مكرر: نُشر بالفعل. لا نشر ثانٍ ولا خطأ.
            return {
                "id": reel_id,
                "state": STAGE_PUBLISHED,
                "published": True,
                "inProgress": False,
                "idempotent": True,
            }, 200

        if state == STAGE_UNVERIFIED:
            raise ApiError(
                "unverified",
                "the last publish is unconfirmed; check Instagram first",
                409,
            )

        if state in IN_FLIGHT_STAGES:
            # أمر مكرر بينما السلسلة تعمل: نعيد الحالة الحالية ولا نبدأ ثانية.
            return {
                "ok": False,
                "errorCode": "in_progress",
                "error": "the server is already publishing this reel",
                "id": reel_id,
                "state": state,
                "published": False,
                "inProgress": True,
            }, 202

        if state == STAGE_FAILED:
            # المحاولة اليدوية تعيد الفاشل إلى الطابور بدل تركه ميتاً للأبد.
            await self.store.transition(
                path,
                (STAGE_FAILED,),
                STAGE_SCHEDULED,
                {"attempts": 0, "error": None, "containerId": None},
            )
            current, _etag = await self.store.read(path)
            current = current if isinstance(current, dict) else current

        if not await self.engine.archive_now(reel_id):
            raise ApiError("not_ready", "the reel could not be archived yet, try again", 503)

        container_id = _clean(current.get("containerId"))
        if not container_id:
            container_id, error = await self.engine.prepare_container(reel_id)
            if not container_id:
                error = error or "instagram did not return a container id"
                await self.store.transition(
                    path,
                    (STAGE_SCHEDULED, STAGE_PREPARED, STAGE_PREPARING),
                    STAGE_FAILED,
                    {"error": error},
                )
                raise ApiError("upstream_failed", error, 503)

        moment = _now()
        fresh, fresh_etag = await self.store.read(path)
        fresh = fresh if isinstance(fresh, dict) else {}
        if _stage_of(fresh) in IN_FLIGHT_STAGES:
            return {
                "ok": False,
                "errorCode": "in_progress",
                "error": "the server is already publishing this reel",
                "id": reel_id,
                "state": _stage_of(fresh),
                "published": False,
                "inProgress": True,
            }, 202
        await self.store.commit(
            path,
            fresh_etag,
            fresh,
            {
                "at": moment,
                "state": STAGE_PREPARED,
                "containerId": container_id,
                "attempts": 0,
                "error": None,
                "stageAt": 0,
                "manualPublish": True,
                "manualAt": moment,
            },
        )

        state, error = await self.engine.advance_until_settled(reel_id)
        if error == ARCHIVE_WAIT:
            error = "the reel archive is not ready on the server"

        published = state in (STAGE_PUBLISHED, STAGE_CLEANING)
        if not published and error is None:
            error = "instagram is still processing the video; press again in a minute"

        await self.store.bump_revision()
        if published:
            return {
                "id": reel_id,
                "state": state,
                "published": True,
                "inProgress": False,
            }, 200
        return {
            "ok": False,
            "errorCode": "in_progress",
            "error": error,
            "id": reel_id,
            "state": state,
            "published": False,
            "inProgress": True,
        }, 202

    # ------------------------------------------------------------------
    # reel.delete
    # ------------------------------------------------------------------

    async def delete(self, reel_id):
        path = f"schedules/{reel_id}.json"
        schedule = await self.store.get(path)
        if isinstance(schedule, dict):
            state = _stage_of(schedule)
            if state in IN_FLIGHT_STAGES:
                raise ApiError("in_progress", "the reel is being published right now", 409)

        key = self.engine.key(reel_id)
        try:
            await self.env.FILES.delete(key)
        except Exception as exc:  # noqa: BLE001
            raise ApiError(
                "storage_failed",
                "could not delete the archived file: %s" % _describe(exc)[:200],
                503,
            )

        if await self.engine.exists(reel_id) is True:
            raise ApiError("storage_failed", "the archived file is still in R2", 503)

        if not await self.engine.forget(reel_id):
            raise ApiError(
                "store_failed",
                "the file is gone but the database records could not be removed",
                503,
            )

        await self.store.bump_revision()
        return {"id": reel_id, "deleted": True}, 200


class CommandQueue:
    """قناة أوامر عبر Firebase: التطبيق يكتب أمراً، والخادم ينفّذه.

    السبب: شبكات كثيرة تحجب نطاق `*.workers.dev` أو تُسقط الاتصال به لفترات،
    فيضيع أمر التطبيق بصمت. Firebase يبقى متاحاً، فأصبح هو الطريق الذي يحمل
    الأمر، والقرار والتنفيذ يبقيان على الخادم وحده.

    شكل الأمر: `commands/<id>` = {type, reelId?, url?, thumbnail?, media?,
    at?, ids?, state, createdAt}. الخادم يحجز الأمر بـCAS (pending -> running)
    ثم يكتب `state: done|failed` و`result` أو `error`، والتطبيق يقرأ النتيجة.
    """

    def __init__(self, env, store, engine, commands):
        self.env = env
        self.store = store
        self.engine = engine
        self.commands = commands

    async def run(self, limit=COMMAND_MAX_PER_RUN):
        raw = await self.store.get(COMMANDS_PATH)
        if not isinstance(raw, dict):
            return {"done": 0, "failed": 0, "pending": 0}

        pending = []
        for raw_id, record in raw.items():
            clean_id = _safe_id(raw_id)
            if not clean_id or not isinstance(record, dict):
                continue
            if _clean(record.get("state"), "pending") != "pending":
                continue
            pending.append((_int_of(record, "createdAt"), clean_id, record))
        pending.sort()

        done = 0
        failed = 0
        for _at, clean_id, record in pending[:limit]:
            path = f"{COMMANDS_PATH}/{clean_id}.json"
            # حجز واحد: الدورة الأخرى (أو /publish اليدوي) لا تنفّذ نفس الأمر.
            # الكتابة مشروطة بـETag، فأول من يقرأ الأمر هو من ينفّذه.
            claimed = False
            for _attempt in range(CAS_ATTEMPTS):
                current, etag = await self.store.read(path)
                if not isinstance(current, dict):
                    break
                if _clean(current.get("state"), "pending") != "pending":
                    break
                try:
                    await self.store.commit(
                        path, etag, current, {"state": "running", "startedAt": _now()}
                    )
                    claimed = True
                except ApiError as exc:
                    if exc.code != "conflict":
                        raise
                    continue
                break
            if not claimed:
                continue
            kind = _clean(record.get("type"))
            try:
                result = await self.dispatch(kind, record)
                await self.store.patch(
                    path,
                    {
                        "state": "done",
                        "ok": True,
                        "result": result,
                        "error": None,
                        "doneAt": _now(),
                    },
                )
                done += 1
            except ApiError as exc:
                await self.store.patch(
                    path,
                    {
                        "state": "failed",
                        "ok": False,
                        "errorCode": exc.code,
                        "error": exc.message,
                        "doneAt": _now(),
                    },
                )
                failed += 1
            except Exception as exc:  # noqa: BLE001 - أمر واحد لا يُسقط الدورة
                print("pigeon command %s failed: %s" % (kind, exc))
                await self.store.patch(
                    path,
                    {
                        "state": "failed",
                        "ok": False,
                        "errorCode": "internal",
                        "error": _describe(exc)[:300],
                        "doneAt": _now(),
                    },
                )
                failed += 1

        await self.prune()
        return {"done": done, "failed": failed, "pending": len(pending)}

    async def dispatch(self, kind, record):
        reel_id = _clean(record.get("reelId")) or _clean(record.get("id"))

        if kind == "archive":
            body, _status = await self.commands.archive(
                _new_request_id(),
                {
                    "id": reel_id,
                    "url": record.get("url"),
                    "thumbnail": record.get("thumbnail"),
                    "media": record.get("media"),
                },
            )
            return {
                "id": body.get("id"),
                "state": body.get("state"),
                "archive": body.get("archive"),
            }

        if kind in ("schedule", "reschedule"):
            body, _status = await self.commands.set_schedule(
                {
                    "id": reel_id,
                    "at": record.get("at"),
                    "timezone": record.get("timezone"),
                }
            )
            return body

        if kind == "delete":
            body, _status = await self.commands.delete(_require_id(reel_id))
            return body

        if kind == "publish_now":
            body, _status = await self.commands.publish_now(_require_id(reel_id))
            return body

        if kind == "order":
            body, _status = await self.commands.reorder(
                _new_request_id(), {"ids": record.get("ids")}
            )
            return body

        raise ApiError("invalid_request", "unknown command type: %s" % (kind or "?"))

    async def prune(self):
        """يحذف الأوامر المنتهية القديمة: تشخيص التطبيق فقط، لا نموّ بلا حد."""
        raw = await self.store.get(COMMANDS_PATH)
        if not isinstance(raw, dict):
            return
        now = _now()
        for raw_id, record in raw.items():
            clean_id = _safe_id(raw_id)
            if not clean_id or not isinstance(record, dict):
                continue
            state = _clean(record.get("state"))
            if state not in ("done", "failed"):
                continue
            stamp = _int_of(record, "doneAt") or _int_of(record, "createdAt")
            if now - stamp < COMMAND_TTL_MS:
                continue
            try:
                await self.store.delete(f"{COMMANDS_PATH}/{clean_id}.json")
            except Exception:  # noqa: BLE001 - تنظيف يختبر نفسه
                continue


# ==========================================================================
# صورة الحالة الكاملة: مصدر الحقيقة الوحيد الذي يقرأه التطبيق
# ==========================================================================


async def _build_state(store, env, request_id=None):
    now = _now()
    schedules = await store.get("schedules") or {}
    reels = await store.get("reels") or {}
    if not isinstance(schedules, dict):
        schedules = {}
    if not isinstance(reels, dict):
        reels = {}

    snapshot = {
        "ok": True,
        "serverTime": now,
        "timezone": SCHEDULE_TIMEZONE,
        "revision": 0,
        "tokenPresent": bool(_ig_token(env)),
        "reels": {},
        "publishing": None,
    }
    if request_id:
        snapshot["requestId"] = request_id

    pending = []
    revision = 0

    for schedule_id, record in schedules.items():
        clean_id = _safe_id(schedule_id)
        if not clean_id or not isinstance(record, dict):
            continue

        reel = reels.get(clean_id)
        archive = reel.get("archive") if isinstance(reel, dict) else None
        view = _reel_view(record, archive, now)
        snapshot["reels"][clean_id] = view

        revision = max(
            revision,
            _int_of(record, "updatedAt"),
            _int_of(record, "scheduledAt"),
            _int_of(record, "publishedAt"),
        )

        if view["state"] in IN_FLIGHT_STAGES:
            snapshot["publishing"] = {
                "id": clean_id,
                "state": view["state"],
                "since": _int_of(record, "stageAt"),
            }
        if view["at"] > 0 and view["state"] not in FINAL_STAGES:
            pending.append((view["at"], clean_id))

    # ترتيب النشر: 1 = الريل القادم، ثم الذي بعده، بلا تكرار درجات.
    pending.sort()
    for index, (_at, clean_id) in enumerate(pending):
        snapshot["reels"][clean_id]["order"] = index + 1

    stored = await store.get(REVISION_PATH)
    revision = max(revision, _int_of(stored, "rev") if isinstance(stored, dict) else 0)
    snapshot["revision"] = revision
    return snapshot


# ==========================================================================
# الطبقة 4 — HTTP: راوتر جدولي، مصادقة واحدة، ورد موحّد
# ==========================================================================

# (method, pattern) → اسم المعالج. {id} مقطع متغيّر.
ROUTES = (
    ("GET", (), "root"),
    ("GET", ("health",), "health"),
    ("GET", ("state",), "state"),
    ("GET", ("schedules",), "state"),
    ("POST", ("schedules",), "set_schedule"),
    ("POST", ("schedules", "order"), "reorder"),
    ("POST", ("publish-now",), "publish_now"),
    ("GET", ("publish",), "publish_run"),
    ("POST", ("publish",), "publish_run"),
    ("POST", ("reels",), "archive_start"),
    ("GET", ("reels", "{id}"), "reel_status"),
    ("DELETE", ("reels", "{id}"), "reel_delete"),
    ("GET", ("download", "{id}"), "download"),
    ("GET", ("media", "{id}"), "media"),
    ("HEAD", ("media", "{id}"), "media"),
)

PUBLIC_HANDLERS = ("root", "health", "media")


def _match_route(method, parts):
    for route_method, pattern, name in ROUTES:
        if route_method != method or len(pattern) != len(parts):
            continue
        params = {}
        matched = True
        for expected, actual in zip(pattern, parts):
            if expected.startswith("{") and expected.endswith("}"):
                params[expected[1:-1]] = actual
            elif expected != actual:
                matched = False
                break
        if matched:
            return name, params
    return None


class Default(WorkerEntrypoint):
    """نقطة الدخول: تحقّق ومصادقة، ثم تفويض إلى الأوامر أو المحرّك.

    لا منطق نشر هنا: الراوتر يعرف المسارات فقط، وكل قرار في Commands/Engine.
    """

    @property
    def store(self):
        db = _clean(getattr(self.env, "FIREBASE_DB", None), RTDB_FALLBACK)
        return Store(db)

    @property
    def engine(self):
        return Engine(self.env, self.store)

    @property
    def commands(self):
        return Commands(self.env, self.store, self.engine)

    @property
    def queue(self):
        return CommandQueue(self.env, self.store, self.engine, self.commands)

    def _authorized(self, request):
        expected = _clean(getattr(self.env, "API_TOKEN", None))
        if not expected:
            return True
        try:
            provided = _clean(request.headers.get("x-api-key"))
        except Exception:  # noqa: BLE001 - طلب بلا ترويسات
            provided = ""
        return provided == expected

    async def fetch(self, request):
        request_id = _new_request_id()
        try:
            request_id = _clean(request.headers.get("x-request-id")) or request_id
        except Exception:  # noqa: BLE001
            pass

        method = _clean(getattr(request, "method", "GET"), "GET").upper()
        parts = tuple(part for part in _path_of(str(request.url)).split("/") if part)

        if method == "OPTIONS":
            return Response(
                "",
                status=204,
                headers={
                    "access-control-allow-origin": "*",
                    "access-control-allow-headers": "content-type,x-api-key,x-request-id",
                    "access-control-allow-methods": "GET,POST,DELETE,HEAD,OPTIONS",
                },
            )

        try:
            route = _match_route(method, parts)
            if route is None:
                raise ApiError("not_found", "unknown route", 404)
            name, params = route
            if name not in PUBLIC_HANDLERS and not self._authorized(request):
                raise ApiError("unauthorized", "unauthorized", 401)

            result = await getattr(self, "h_" + name)(request, params, request_id)
            if isinstance(result, Response):
                return result

            payload, status = result
            return _reply(payload, status, request_id)
        except ApiError as exc:
            return _fail(exc.code, exc.message, exc.status, request_id)
        except Exception as exc:  # noqa: BLE001 - لا نُسقط الطلب بلا رد
            print("pigeon request failed: %s" % _describe(exc))
            return _fail("internal", _describe(exc)[:300], 500, request_id)

    # ------------------------------------------------------------------
    # المعالجات
    # ------------------------------------------------------------------

    async def h_root(self, request, params, request_id):
        return {
            "service": "pigeon-reels",
            "timezone": SCHEDULE_TIMEZONE,
            "tokenPresent": bool(_ig_token(self.env)),
            "routes": [
                "GET /state",
                "POST /schedules",
                "POST /schedules/order",
                "POST /publish-now",
                "POST /reels",
                "GET /reels/<id>",
                "DELETE /reels/<id>",
                "GET /download/<id>",
                "GET /media/<id>",
                "GET|POST /publish",
            ],
        }, 200

    async def h_health(self, request, params, request_id):
        return {
            "health": True,
            "tokenPresent": bool(_ig_token(self.env)),
            "timezone": SCHEDULE_TIMEZONE,
        }, 200

    async def h_state(self, request, params, request_id):
        snapshot = await _build_state(self.store, self.env, request_id)
        return snapshot, 200

    async def h_set_schedule(self, request, params, request_id):
        body = await _body_of(request)
        return await self.commands.set_schedule(body)

    async def h_reorder(self, request, params, request_id):
        body = await _body_of(request)
        return await self.commands.reorder(request_id, body)

    async def h_publish_now(self, request, params, request_id):
        body = await _body_of(request)
        reel_id = _require_id(_field(body, "id"))
        return await self.commands.publish_now(reel_id)

    async def h_archive_start(self, request, params, request_id):
        body = await _body_of(request)
        return await self.commands.archive(request_id, body)

    async def h_reel_status(self, request, params, request_id):
        reel_id = _require_id(params.get("id"))
        now = _now()
        schedule = await self.store.get(f"schedules/{reel_id}.json")
        reel = await self.store.get(f"reels/{reel_id}.json")
        archive = reel.get("archive") if isinstance(reel, dict) else None

        if not isinstance(schedule, dict) and not isinstance(reel, dict):
            raise ApiError("not_found", "unknown reel", 404)

        view = _reel_view(schedule or {}, archive, now)
        return {
            "id": reel_id,
            "state": view["state"],
            "archive": view["archive"],
            "allowedActions": view["allowedActions"],
            "downloadable": view["downloadable"],
            "publishable": view["publishable"],
            "at": view["at"],
            "remainingMs": view["remainingMs"],
            "error": view["error"],
        }, 200

    async def h_reel_delete(self, request, params, request_id):
        reel_id = _require_id(params.get("id"))
        return await self.commands.delete(reel_id)

    async def h_publish_run(self, request, params, request_id):
        summary = await self._publish_due()
        await self._record_run(summary)
        return summary, 200

    async def h_download(self, request, params, request_id):
        reel_id = _require_id(params.get("id"))
        obj = await self.env.FILES.get(self.engine.key(reel_id))
        if obj is None:
            raise ApiError("not_found", "not stored yet", 404)
        return Response(
            obj.body,
            headers={
                "content-type": "video/mp4",
                "content-disposition": 'attachment; filename="pigeon-%s.mp4"' % reel_id,
                "cache-control": "public, max-age=86400",
                "access-control-allow-origin": "*",
            },
        )

    async def h_media(self, request, params, request_id):
        reel_id = _require_id(params.get("id"))
        method = _clean(getattr(request, "method", "GET"), "GET").upper()
        key = self.engine.key(reel_id)
        headers = {
            "content-type": "video/mp4",
            "content-disposition": 'inline; filename="pigeon-%s.mp4"' % reel_id,
            "cache-control": "public, max-age=86400",
            "access-control-allow-origin": "*",
        }

        if method == "HEAD":
            obj = await self.env.FILES.head(key)
            if obj is None:
                raise ApiError("not_found", "not stored yet", 404)
            size = getattr(obj, "size", None)
            if size is not None:
                headers["content-length"] = str(size)
            return Response("", headers=headers)

        obj = await self.env.FILES.get(key)
        if obj is None:
            raise ApiError("not_found", "not stored yet", 404)
        # Meta تحتاج content-length صريحاً لتقبل تنزيل الفيديو من هذا الرابط.
        size = getattr(obj, "size", None)
        if size is not None:
            headers["content-length"] = str(size)
        headers["accept-ranges"] = "bytes"
        return Response(obj.body, headers=headers)

    # ------------------------------------------------------------------
    # cron: تشغيل السلسلة للمواعيد المستحقة
    # ------------------------------------------------------------------

    async def scheduled(self, controller, env, ctx):
        try:
            summary = await self._publish_due()
        except Exception as exc:  # noqa: BLE001 - لا نُفشل الـcron كله
            summary = {"ok": False, "error": _describe(exc)[:300], "serverTime": _now()}
            print("pigeon schedule run failed: %s" % exc)
        await self._record_run(summary)

    async def _publish_due(self):
        store = self.store
        engine = self.engine
        owner = "cron"

        if not await _acquire_mutex(store, owner):
            return {
                "ok": True,
                "skipped": "another operation is running",
                "serverTime": _now(),
            }

        try:
            now = _now()
            report = {
                "ok": True,
                "checkedAt": now,
                "tokenPresent": bool(_ig_token(self.env)),
                "total": 0,
                "due": 0,
                "results": [],
                "archived": 0,
            }

            # 1) أوامر التطبيق المعلّقة: تُنفَّذ قبل قراءة المواعيد لأنها قد
            #    تُنشئ موعداً أو تحذف ريلاً. هذه هي القناة التي تصل حتى لو
            #    حجب مقدّم الخدمة نطاق الـWorker عن الهاتف.
            try:
                report["commands"] = await self.queue.run()
            except Exception as exc:  # noqa: BLE001 - لا نُسقط الدورة
                print("pigeon command queue failed: %s" % exc)
                report["commands"] = {"ok": False, "error": _describe(exc)[:200]}

            # 2) أي ريل مسجّل بلا ملف في R2 يُؤرشف الآن، ولو لم يطلبه التطبيق.
            try:
                report["archived"] = await engine.archive_pending()
            except Exception as exc:  # noqa: BLE001 - ليس حرجاً
                print("pigeon pending archive failed: %s" % exc)
            schedules = await store.get("schedules") or {}
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
                if state in FINAL_STAGES:
                    continue
                at = _int_of(schedule, "at")
                clean_id = _safe_id(schedule_id)
                if at <= 0 or not clean_id:
                    continue
                work.append((at, clean_id, schedule))

            work.sort(key=lambda entry: entry[0])
            report["due"] = len(work)

            for at, clean_id, schedule in work[:MAX_PUBLISH_PER_RUN]:
                entry = {"id": clean_id, "at": at, "state": _stage_of(schedule)}
                try:
                    state, error = await engine.advance(clean_id, schedule)
                    entry["state"] = state
                    if error:
                        entry["error"] = error
                except Exception as exc:  # noqa: BLE001 - لا نُسقط بقية المواعيد
                    print("pigeon schedule %s failed: %s" % (clean_id, exc))
                    entry["error"] = _describe(exc)[:300]
                report["results"].append(entry)

            waiting_archive = any(
                entry.get("error") == ARCHIVE_WAIT for entry in report["results"]
            )
            if waiting_archive:
                try:
                    report["archived"] = int(report.get("archived") or 0) + int(
                        await engine.archive_missing()
                    )
                except Exception as exc:  # noqa: BLE001 - ليس حرجاً
                    print("pigeon archive retry failed: %s" % exc)

            await store.bump_revision()
            return report
        finally:
            await _release_mutex(store, owner)

    async def _record_run(self, summary):
        """يحفظ نتيجة آخر تشغيل تحت `/worker/status` لتظهر من أي متصفح."""
        try:
            store = self.store
            await store.put("worker/status/lastRun.json", summary)
            await store.patch("worker/status.json", {"updatedAt": _now()})
        except Exception:  # noqa: BLE001 - تشخيص فقط
            return

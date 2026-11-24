# Pigeon Reels API (Cloudflare Python Worker)

ينزّل ريل إنستغرام أو فيديو تيك توك من رابطه، يخزّنه في R2، ويكتب تقدّم
المعالجة في Firebase تحت `/reels/<id>/archive` حتى يعرضه التطبيق كنسبة 0..100،
ثم يقدّم الملف من `GET /download/<id>` لتحميله على الجهاز.

كذلك ينشر الريلز المجدولة من Cloudflare إلى إنستغرام عند موعدها عبر
Instagram Graph API، من cron داخلي، وليس من جدولة إنستغرام نفسها.

## المسارات

| المسار | الوظيفة |
| --- | --- |
| `GET /` | معلومات الخدمة |
| `POST /reels` | `{"id","url","thumbnail"}` → 202 ويبدأ التنزيل والتخزين |
| `GET /reels/<id>` | حالة الأرشفة (`state`, `progress`, `url`, `bytes`, `error`) |
| `GET /download/<id>` | ملف الـmp4 من R2 بـ`Content-Disposition: attachment` |
| `GET /media/<id>` | نفس الملف للعرض العام (تقرأه Meta وقت النشر، بلا مفتاح) |

`state` تكون: `queued` → `downloading` (0..92) → `storing` → `ready`، أو `failed`.

## النشر المجدول إلى إنستغرام

- التطبيق يكتب الموعد في Firebase تحت `/schedules/<id>` (منفصل عن سجل الريل
  حتى لا يمسحه PATCH):
  `{"at": 1764000000000, "timezone": "Africa/Algiers", "state": "scheduled"}`.
- `wrangler.jsonc` فيه cron كل 5 دقائق (`*/5 * * * *`). عند حلول الموعد:
  1. يتأكد أن الفيديو موجود في R2 (`reels/<id>.mp4`).
  2. ينشئ Instagram media container بـ`media_type=REELS` ورابط
     `PUBLIC_BASE_URL/media/<id>`.
  3. يفحص حالة الحاوية كل تشغيل حتى `FINISHED` (أو `ERROR`/`EXPIRED`).
  4. ينادي `media_publish` ويحفظ `mediaId` و`publishedAt`.
- حالات الموعد: `scheduled` → `creating` → `publishing` → `published`، أو
  `failed` مع `error`. لو انقطع الـWorker بعد `media_publish` لا يعيد النشر
  تلقائياً (حتى لا يتكرر الريل)، بل يعلّم الحالة `failed` للمراجعة.
- الوقت ثابت 19:00 بتوقيت الجزائر (`Africa/Algiers` = UTC+1) أي 18:00 UTC.
- `IG_ACCESS_TOKEN` سرّ في GitHub Actions وليس في `wrangler.jsonc`؛ يحتاج
  صلاحية `instagram_content_publish`. `IG_USER_ID` و`PUBLIC_BASE_URL` في
  `vars`.

## النشر

الـWorker يُنشر من GitHub Actions فقط (`.github/workflows/api.yml`)، بدون أي
تثبيت محلي. أضف الأسرار في المستودع من
`Settings → Secrets and variables → Actions`:

| السر | من وين تجيبه |
| --- | --- |
| `CLOUDFLARE_ACCOUNT_ID` | Cloudflare Dashboard → Workers & Pages → Account ID |
| `CLOUDFLARE_API_TOKEN` | My Profile → API Tokens → Create Token بصلاحيات **Workers Scripts: Edit** و **R2: Edit** |
| `IG_ACCESS_TOKEN` | توكن إنستغرام طويل الأمد لنفس الحساب، بصلاحية `instagram_content_publish` |

بديل للتوكن: `CLOUDFLARE_EMAIL` (بريد حساب Cloudflare) مع `CLOUDFLARE_API_KEY`
(Global API Key من My Profile → API Tokens → Global API Key). يكفي واحد من
الخيارين.

بعدها: Actions → **Deploy Pigeon Reels API** → Run workflow. السكربت ينشئ
bucket باسم `water` (مرة وحدة) ثم ينشر الـWorker على
`https://brainrot-pigeon-reels.brainrotpigeon.workers.dev`.

مطلوب تفعيل R2 من لوحة Cloudflare أول مرة (يحتاج بطاقة، والاستخدام المجاني
10GB تخزين).

## اختياري: قفل الـAPI

إذا أضفت سرّاً للـWorker باسم `API_TOKEN` (Workers → الإعدادات → Variables)
فسيصير كل طلب يحتاج ترويسة `X-Api-Key` مطابقة، وتضبط نفس القيمة في التطبيق عبر
`pigeon.archiveToken` في `android/local.properties`. بدون السر تبقى الخدمة
مفتوحة لكنها تقبل روابط إنستغرام وتيك توك فقط وبحد أقصى 48MB للفيديو.

## ملاحظات

- الروابط المدعومة:
  - إنستغرام: `instagram.com/reel|reels|p|tv/<code>`.
  - تيك توك: `tiktok.com/@user/video/<id>` وأي رابط مختصر `vm/vt.tiktok.com`
    أو `tiktok.com/t/<code>` (يُتبَّع تحويله أولاً).
  - `POST /reels` يقبل `{"id", "url", "thumbnail"?, "media"?}` حيث `media`
    رابط CDN تيك توك النظيف الذي حلّه التطبيق.
- إنستغرام: رابط الفيديو يُستخرج من صفحة `embed` العامة، وبدون تسجيل دخول.
- تيك توك: Cloudflare لا يستطيع حل روابط تيك توك بنفسه: كل رابط يخرج من
  صفحة الفيديو هو `*-prime.tiktok.com` موقّع ويحتاج كوكي `tt_chain_token`
  (يرد 403 حتى من شبكة عادية)، وtikwm يرد على عناوين Cloudflare بحصة يومية
  مشتركة (`Free Api Limit`). لذلك يحلّ التطبيق الرابط من شبكة الهاتف ويرسل
  رابط `*.tiktokcdn.com` النظيف (بلا علامة مائية) في حقل `media`، والـWorker
  ينزّله ويخزنه في R2. الـWorker لا يقبل في `media` إلا مضيفات CDN تيك توك.
  إن غاب `media` جرّب الـWorker صفحة الفيديو ثم tikwm كاحتياط.
- إذا حجب المنصّة الطلب من عنوان Cloudflare يفشل السجل بحالة `failed` مع سبب
  واضح في حقل `error`.

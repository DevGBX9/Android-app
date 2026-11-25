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
| `DELETE /reels/<id>` | حذف الريل: يمسح ملفه من R2 وكل معلوماته من Firebase (409 أثناء النشر) |
| `GET /download/<id>` | ملف الـmp4 من R2 بـ`Content-Disposition: attachment` |
| `GET /media/<id>` | نفس الملف للعرض العام (تقرأه Meta وقت النشر، بلا مفتاح) |
| `POST /publish-now` | `{"id"}` → ينشر الريل الآن ويتجاهل موعده، ثم يحذف الملف والسجلات |
| `POST /schedules/order` | `{"ids":[...]}` → يرتّب الريلز: المواعيد نفسها تُوزّع حسب الترتيب (سحب ريل 26 فوق ريل 29 يتبادل الموعدين) |

`state` تكون: `queued` → `downloading` (0..92) → `storing` → `ready`، أو `failed`.

`POST /publish-now` لا ينتظر الموعد ولا cron: ينتظر أرشفة الفيديو إن لم تكن
مكتملة، ينشئ حاوية إنستغرام في نفس اللحظة، ينتظرها حتى تصير `FINISHED`، ثم
`media_publish` وتأكيد وحذف الملف من R2 وسجلات Firebase — كل ذلك قبل الردّ
(ميزانية 90 ثانية). إن لم تنتهِ المعالجة عند Meta في المهلة يردّ 202 مع سبب
واضح، ويبقى السجل `prepared` ليكمل الـcron أو ضغطة تالية بدل الانتهاء الصامت.

`POST /schedules/order` يستقبل معرّفات الريلز بترتيب الشبكة الجديد (من الأعلى
إلى الأسفل) ولا يغيّر قيم المواعيد: أصغر موعد يذهب لأول ريل، والذي بعده للثاني،
وهكذا — فسحب ريل 26 نوفمبر فوق ريل 29 نوفمبر يعطي الأول 26 والثاني 29. الموعد
يعود إلى `scheduled` وتُلغى حاوية Meta المحضّرة لأن أوان النشر تغيّر، فتُحضّر
حاوية جديدة عند الموعد الجديد. يُرفض الطلب 409 إذا كان أحد الريلز يُنشر الآن أو
حان موعده أو لو تعارض الترتيب الجديد مع موعد ريل آخر لم يشمله السحب.

## النشر المجدول إلى إنستغرام

- التطبيق يكتب الموعد في Firebase تحت `/schedules/<id>` (منفصل عن سجل الريل
  حتى لا يمسحه PATCH):
  `{"at": 1764000000000, "timezone": "Africa/Algiers", "state": "scheduled"}`.
- `wrangler.jsonc` فيه cron كل دقيقة (`* * * * *`).
- السلسلة ثابتة ومرتّبة، ولكل خطوة حالتها وحدها؛ لا تبدأ خطوة إلا بعد أن
  تنتهي التي قبلها بنتيجة (نجاح أو فشل):

  | الحالة | الخطوة | نتيجتها |
  | --- | --- | --- |
  | `scheduled` | انتظار الموعد | `preparing` عند دخول نافذة الـ45 دقيقة |
  | `preparing` | إنشاء حاوية إنستغرام | `prepared` أو `scheduled` (إعادة) أو `failed` |
  | `prepared` | انتظار الموعد ثم فحص الحاوية | `publishing` عند `FINISHED` |
  | `publishing` | `media_publish` | `verifying` أو `prepared` (إعادة) أو `failed` |
  | `verifying` | `GET /<media_id>` من Graph API | `cleaning` أو `unverified` |
  | `cleaning` | تأكيد ثانٍ ← حذف R2 ← حذف Firebase | تُحذف السجلات (نهاية ناجحة) |

- منع التداخل: كل انتقال يُكتب بـ`PUT` مشروط بـ`if-match` (ETag من Firebase)،
  وكل خطوة تحجز نفسها قبل النداء الخارجي. تشغيلان في نفس اللحظة: الثاني يفشل
  بـ412 ولا ينشر ولا يحذف شيئاً (مُختبر).
  سبب `PUT` لا `PATCH`: Firebase RTDB يرفض ترويسة `if-match` على PATCH (يرد
  400)، فيُقرأ السجل ثم تُدمج الحقول الجديدة فوقه وتُكتب بـ`PUT`؛ التطابق
  بـ`if-match` يضمن أن السجل لم يتغيّر بين القراءة والكتابة. ولا تكتب أي خطوة
  بلا ETag.
- لو انقطع الـWorker بعد `media_publish` لا يعيد النشر أبداً: يسأل الحساب عن
  آخر منشور أولاً. ولو بقي النشر غير مؤكد (`unverified`) يبقى الملف في R2
  وسجلات Firebase كما هي — لا حذف بلا تأكيد من إنستغرام.
- التنظيف: بعد نجاح النشر بتأكيد Graph API ينتظر 5 ثوانٍ، يتأكد مرة أخرى،
  يحذف `reels/<id>.mp4` من R2، يتأكد أنه غاب (`head`)، ثم يحذف `reels/<id>`
  و`schedules/<id>` من Firebase. كل ذلك في نفس تشغيل cron.
- سجل `reels/<id>/archive` يُحدَّث بـ`PATCH` (دمج)، وحالة `ready` نهائية: أي
  محاولة أرشفة فاشلة بعدها لا ترجعه إلى `failed` ولا تمسح رابطه وحجمه.
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

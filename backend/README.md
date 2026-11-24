# Pigeon Reels API (Cloudflare Python Worker)

ينزّل ريل إنستغرام من رابطه، يخزّنه في R2، ويكتب تقدّم المعالجة في Firebase
تحت `/reels/<id>/archive` حتى يعرضه التطبيق كنسبة 0..100، ثم يقدّم الملف من
`GET /download/<id>` لتحميله على الجهاز.

## المسارات

| المسار | الوظيفة |
| --- | --- |
| `GET /` | معلومات الخدمة |
| `POST /reels` | `{"id","url","thumbnail"}` → 202 ويبدأ التنزيل والتخزين |
| `GET /reels/<id>` | حالة الأرشفة (`state`, `progress`, `url`, `bytes`, `error`) |
| `GET /download/<id>` | ملف الـmp4 من R2 بـ`Content-Disposition: attachment` |

`state` تكون: `queued` → `downloading` (0..92) → `storing` → `ready`، أو `failed`.

## النشر

الـWorker يُنشر من GitHub Actions فقط (`.github/workflows/api.yml`)، بدون أي
تثبيت محلي. أضف سرّين في المستودع من
`Settings → Secrets and variables → Actions`:

| السر | من وين تجيبه |
| --- | --- |
| `CLOUDFLARE_ACCOUNT_ID` | Cloudflare Dashboard → Workers & Pages → Account ID |
| `CLOUDFLARE_API_TOKEN` | My Profile → API Tokens → Create Token بصلاحيات **Workers Scripts: Edit** و **R2: Edit** |

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
- إنستغرام: رابط الفيديو يُستخرج من صفحة `embed` العامة، وبدون تسجيل دخول.
- تيك توك: روابط `playAddr` الموقّعة ترفض التنزيل بـ403 بدون كوكي
  `tt_chain_token`، لذلك نأخذ رقم الفيديو من تحويل الرابط المختصر أو من
  `oembed` ثم ننزّل نسخة tikwm النظيفة بلا علامة مائية من
  `tikwm.com/video/media/play/<id>.mp4`. هذا المسار لا يحتاج تسجيلاً ولا حصة
  API، لكنه يرفض `HEAD` وطلبات `Range`، فالتنزيل بطلب كامل واحد. إن فشل
  نجرّب API الخاص بـ tikwm ثم بيانات صفحة الفيديو.
- إذا حجب المنصّة الطلب من عنوان Cloudflare يفشل السجل بحالة `failed` مع سبب
  واضح في حقل `error`.

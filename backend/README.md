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

بعدها: Actions → **Deploy Pigeon Reels API** → Run workflow. السكربت ينشئ
bucket باسم `pigeon-reels` (مرة وحدة) ثم ينشر الـWorker على
`https://brainrot-pigeon-reels.<account-subdomain>.workers.dev`.

مطلوب تفعيل R2 من لوحة Cloudflare أول مرة (يحتاج بطاقة، والاستخدام المجاني
10GB تخزين).

## اختياري: قفل الـAPI

إذا أضفت سرّاً للـWorker باسم `API_TOKEN` (Workers → الإعدادات → Variables)
فسيصير كل طلب يحتاج ترويسة `X-Api-Key` مطابقة، وتضبط نفس القيمة في التطبيق عبر
`pigeon.archiveToken` في `android/local.properties`. بدون السر تبقى الخدمة
مفتوحة لكنها تقبل روابط إنستغرام فقط وبحد أقصى 48MB للريل.

## ملاحظات

- الروابط المدعومة حالياً: `instagram.com/reel|reels|p|tv/<code>` فقط.
- رابط الفيديو يُستخرج من صفحة `embed` العامة، وبدون تسجيل دخول. إذا حجب
  إنستغرام الطلب من عنوان Cloudflare يفشل السجل بحالة `failed` مع سبب واضح.

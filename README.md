# Android-app — brainrot.pigeon

تطبيق Android أصلي (Kotlin + Jetpack Compose) يعرض إحصائيات حساب
إنستغرام `@brainrot.pigeon` لايف مباشرة من Instagram Graph API بتوكن مدمج
في البناء، ويستقبل روابط وملفات Reels وTikTok من Share Sheet.

## تحميل التطبيق

1. افتح تبويب **Actions** في هذا المستودع واختر آخر تشغيل ناجح.
2. نزّل `app-debug` من قسم **Artifacts**.
3. أو نزّل APK مباشرة من صفحة **Releases** (إصدار `latest`) —
   يُحدَّث تلقائياً مع كل دفعة إلى `main`.

ثبّت `app-debug.apk` على هاتف Android 8.0 (API 26) أو أحدث، مع السماح
بتثبيت التطبيقات من مصادر غير معروفة.

## البناء

البناء يتم على GitHub Actions فقط:

- `Build Android APK` → `.github/workflows/android.yml`
- يُنتج: `app-debug.apk` (قابل للتثبيت) و`app-release.aab` (غير موقّع).
- اختبارات JVM لمحلل روابط المشاركة تعمل ضمن نفس المهمة.

## هيكل المشروع

```
android/            تطبيق Android (Kotlin + Compose)
worker/             بروكسي Instagram Graph API على Cloudflare
docs/ANDROID.md     البنية، ADB، R2، وطريقة التشغيل المحلي
index.html ...      نسخة الويب الأصلية (مرجع)
```

## ملاحظات

- توكن إنستغرام و`IG_USER_ID` مدمجان في البناء؛ لا توجد شاشة إعدادات داخل التطبيق.
- التجاوز وقت البناء فقط عبر `android/local.properties`
  (`pigeon.igUserId` و`pigeon.igAccessToken`).
- التفاصيل الكاملة في [`docs/ANDROID.md`](docs/ANDROID.md).

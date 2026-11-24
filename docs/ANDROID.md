# تطبيق Android — brainrot.pigeon

تطبيق Android أصلي (Kotlin + Jetpack Compose) لحساب `@brainrot.pigeon`: يعرض إحصائيات
إنستغرام لايف، ويستقبل روابط وملفات Reels وTikTok من Share Sheet في قائمة ريلز محفوظة
على Firebase Realtime Database.

هذا المستودع فيه **التطبيق فقط**؛ ما فيه موقع ويب ولا صفحات HTML/CSS/JS قديمة.

---

## 1. شاشة الحساب

- `ProfileCard` بخلفية `#0c1015` داكنة، فقاعة "Can't decide..." (`NoteBubble`، أفاتار
  88dp، زوايا 22dp)، أفاتار دائري وشارة توثيق مرسومة بـ Canvas.
- عدّاد أرقام متدحرج (`RollingNumber` + `RollingDigit`، 720ms): كل خانة رقم عمود
  من خليتين ينزلق بـ `graphicsLayer.translationY`، وعند تغيّر عدد الخانات يُعاد
  البناء مع أنيميشن pop. لا WebView ولا HTML إطلاقاً.
- صورة البروفايل: رمادي ثم الجديدة تطفو — matrix تشبع/سطوع + fade + scale 1.06 → 1.
- تحديث كل 5 ثوانٍ مع فاصل أدنى ثانيتين وسباق بين البروكسي وGraph
  (`ProfileViewModel` + `ProfileRepository`: Mutex + min gap + race).
- كاش آخر بيانات في DataStore (`pigeon_profile`) وSkeleton أثناء الجلب — لا أرقام
  افتراضية إطلاقاً.

## 2. بنية المشروع

```
android/
  app/src/main/java/com/brainrot/pigeon/
    AppContainer.kt          # تجميع التبعيات (OkHttp، DataStore، المستودعات)
    MainActivity.kt          # نقطة الدخول + التبويبات + onNewIntent
    data/                    # Profile, ProfileRepository, Settings, Saved, FirebaseReels, ReelThumbnails, AvatarLoader
    share/                   # ShareParser, ShareIntake, ShareReceiverActivity
    ui/                      # الشاشات + ViewModels + components + theme
  app/src/test/              # اختبارات JVM لمحلل الروابط (تعمل على CI)
  gradle/libs.versions.toml  # كل الإصدارات في مكان واحد
```

الطبقات: `ui` لا تتصل بالشبكة مباشرة، بل عبر `Repository`؛ و`AppContainer` هو المكان
الوحيد لإنشاء التبعيات، فأي إضافة مستقبلية (شاشة، API، تخزين) تُضاف بسهولة.

## 3. البناء والتثبيت على الهاتف (Debug فقط)

> البناء النهائي (AAB/APK للإصدار) يتم **فقط** على GitHub Actions. الأوامر هنا للتجربة
> على جهازك عبر USB.

المتطلبات على الكمبيوتر:
1. JDK 17 (Temurin مثلاً).
2. Android SDK: `platform-tools` (فيه `adb`) و`platforms;android-35` و`build-tools;35.0.0`.
   يمكن تثبيتها بـ `sdkmanager` من cmdline-tools.
3. Gradle 8.13 (أو شغّل `gradle wrapper --gradle-version 8.13` مرة واحدة لإنشاء `./gradlew`).

بعدها:

```bash
# من مجلد android/
adb devices                      # لازم يظهر جهازك بحالة device
gradle :app:installDebug         # يبني Debug ويثبته مباشرة
adb shell am start -n com.brainrot.pigeon.debug/com.brainrot.pigeon.MainActivity
adb logcat --pid=$(adb shell pidof -s com.brainrot.pigeon.debug)
```

تثبيت APK موجود بدون إعادة بناء:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

ملاحظة: نسخة الـ Debug تستخدم `applicationId` مختلف
(`com.brainrot.pigeon.debug`) حتى تنزل جنب النسخة النهائية.

`IG_USER_ID` الافتراضي يُقرأ من `local.properties` (غير مرفوع إلى Git):

```properties
sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
pigeon.igUserId=17841438848736931
pigeon.igAccessToken=<اختياري: تجاوز التوكن المدمج>
```

**توكن إنستغرام مدمج في البناء** عبر `BuildConfig.DEFAULT_IG_ACCESS_TOKEN`
(قيمته الافتراضية في `android/app/build.gradle.kts`)، ولا توجد شاشة إعدادات
لتعديله. هذا مقصود لأن التطبيق شخصي للاستخدام الخاص.

## 4. GitHub Actions (البناء الفعلي)

الملف: `.github/workflows/android.yml`.

- يعمل على `push` إلى `main`، وعلى Pull Request، وعلى أي وسم `v*`، ويدوياً.
- يثبّت JDK 17 وGradle 8.13، ويشغّل `testDebugUnitTest` ثم `assembleDebug` فقط.
- يرفع أثراً واحداً `brainrot-pigeon-apk`، وينشر الـ APK في Release باسم `latest`
  مع كل دفعة (عدا Pull Requests).

توقيع الإصدار (اختياري لكن مطلوب للنشر على Google Play). أضف هذه الأسرار في
Settings → Secrets and variables → Actions:

| Secret | القيمة |
| --- | --- |
| `KEYSTORE_BASE64` | ناتج `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | كلمة سر الـ keystore |
| `KEY_ALIAS` | alias المفتاح |
| `KEY_PASSWORD` | كلمة سر المفتاح |

إذا لم توجد الأسرار، يُبنى AAB غير موقّع (ينجح البناء، لكن لا يُنشر على Play).

## 5. استقبال المشاركة (Share Sheet)

`ShareReceiverActivity` شاشة شفافة فوق التطبيق المُشارِك (بوب-أب Compose في
المنتصف، View/Close)، مسجّلة في المانيفست لـ:

- `ACTION_SEND` مع `text/plain` (روابط إنستغرام/TikTok).
- `ACTION_SEND` مع `image/*` و`video/*`.
- `ACTION_SEND_MULTIPLE` مع صور/فيديو.
- `ACTION_VIEW` لروابط `instagram.com/reel|reels` و`tiktok.com` و`vm.tiktok.com`،
  ورابط التطبيق `brainrotpigeon://share`.

عند المشاركة:
1. يُحلَّل النص ويُستخرج أول رابط (`ShareParser`، نقي وقابل للاختبار).
2. تُطبَّع روابط إنستغرام إلى `https://www.instagram.com/reel/<code>` مع حذف
   باراميترات التتبع، وروابط TikTok تُحفظ كما هي بدون query.
3. الملفات (فيديو/صورة) تُنسخ إلى `filesDir/saved/` بحد أقصى 150MB، لأن صلاحية
   Uri من التطبيق المُرسل مؤقتة.
4. لا ينتقل المستخدم خارج إنستغرام: شاشة المشاركة شفافة وتعرض بوب-أب في منتصف
   الشاشة فوق إنستغرام: «Reel added» + اسم الريل، وزرّا **View** و**Close**.
   View يفتح التطبيق على شبكة الريلز، وClose يرجّعك لإنستغرام فوراً.
   (لا تحتاج `SYSTEM_ALERT_WINDOW`: النافذة عبارة عن Activity شفافة شرعية فوق
   التطبيق الذي شارك.)
5. السجل يُرفع فوراً إلى Firebase Realtime Database (انظر القسم 6).

كل نصوص الواجهة بالإنجليزية فقط؛ العربية باقية في تعليقات الكود والتوثيق.

الصلاحيات: المشاركة نفسها لا تحتاج أي صلاحية. `READ_MEDIA_IMAGES/VIDEO`
و`READ_EXTERNAL_STORAGE` (حتى API 32) موجودة فقط للاختيار اليدوي المستقبلي.
`FileProvider` مضبوط لمشاركة الملفات المحفوظة للخارج.

## 6. الريلز المجدولة (Firebase + الشبكة)

- **الإضافة**: طريقها الوحيد مشاركة ريل من إنستغرام مع التطبيق (Share Sheet).
- **الشبكة**: صفحة ثانية بجانب الحساب داخل `VerticalPager`، بتصميم إنستغرام
  (شريط أيقونة الشبكة + ٣ أعمدة ريلز **قائمة 9:16** بفواصل 2dp). كل خانة تعرض
  صورة الغلاف الحقيقية من إنستغرام، وشريط تعتيم سفلي ليظهر كود الريل،
  وشارة `✓` على السجل المسجل. السحب للأعلى يرفع صفحة الحساب بإنميشن،
  والسحب للأسفل أو زر الرجوع يرجّعها. لا يوجد شريط علوي ولا زر قائمة.
- **نعومة السحب**: `VerticalPager` يُجهّز الصفحتين مسبقاً
  (`beyondViewportPageCount = 1`) فما تُبنى الشبكة وسط حركة الإصبع، وسلوك
  الإفلات `PagerDefaults.flingBehavior` بحركة `tween` 280ms وعتبة تبديل
  0.16 بدل النابض (فما يرجع الصفحة للوراء). تحديث الحساب الدوري (5 ثوانٍ)
  يتوقف والشبكة ظاهرة عبر `ProfileViewModel.setPollingEnabled(false)`،
  وصور الخانات تُفكّ بحجم مصغّر (`AvatarLoader.load(url, targetSize = 360)`)
  لتخفيف الضغط على الذاكرة أثناء التمرير.
- **صور الغلاف**: `ReelThumbnails` يجيب غلاف **الفيديو الأصلي** من المنصتين
  ويحفظه مع السجل محلياً وفي Firebase:
  - إنستغرام: oEmbed العامة
    (`https://www.instagram.com/api/v1/oembed/?url=<reel>`) → `thumbnail_url`
    (تم التحقق: JPEG 640×640).
  - تيك توك: oEmbed العامة أولاً، وإن ما رجّعت صورة (معظم الفيديوهات حالياً
    ما ترجع `thumbnail_url`) نقرأ صفحة `https://m.tiktok.com/v/<id>.html`
    ونأخذ رابط الغلاف الموقّع منها (`...cover:720:720.jpeg?...`، تم التحقق:
    JPEG 200 OK). روابط `vm/vt.tiktok.com` و`/t/...` تُتبع تحويلاتها أولاً
    لاستخراج كود الفيديو.
  - الريلز القديمة بلا صورة تُجلب لها عند فتح الشبكة (حد أقصى 30 محاولة لكل
    فتح). لو فشل الجلب تبقى الخانة بتدرّج داكن وأيقونة ريل بدل الصورة.
- **أرشفة تيك توك**: Cloudflare لا يقدر يحل روابط تيك توك (روابط الصفحة
  `*-prime` ترد 403 وtikwm يحجب عناوين Cloudflare)، فـ`TikTokMedia` يحل
  الرابط من شبكة الهاتف إلى `*.tiktokcdn.com` بلا علامة مائية ويرسله في حقل
  `media` مع طلب الأرشفة؛ الـWorker ينزّله ويخزنه في R2 كإنستغرام تماماً.
  و`SavedRepository` ينتظر حالة المهمة ولا يعيد الطلب إلا إذا توقف التقدّم أو
  فشلت المهمة، ولا تُنقص النسبة المعروضة أبداً.
- **قاعدة البيانات**: `FirebaseReels` يكلّم RTDB عبر REST بدون SDK:

```
PUT  <db>/reels/<id>.json      # رفع/تحديث سجل
GET  <db>/reels.json           # كل الريلز (null = فاضية)
```

```json
{
  "platform": "instagram",
  "kind": "reel",
  "url": "https://www.instagram.com/reel/<code>",
  "title": "Instagram Reel · <code>",
  "thumbnail": "https://.../cover.jpg",
  "registered": true,
  "source": "android",
  "createdAt": 1763800000000
}
```

- `registered` = السجل موجود ومسجّل فعلاً في قائمة التطبيق؛ يصير `true` بعد
  نجاح الرفع أو بعد سحبه من القاعدة.
- `SavedRepository` يكتب DataStore أولاً (فوري بدون شبكة) ثم يرفع في
  `CoroutineScope` على مستوى `AppContainer`، فلا يُلغى الرفع بإغلاق شاشة المشاركة.
- عند كل فتح للتطبيق: `MainActivity` ينادي `syncFromRemote()` فيسحب القائمة
  من Firebase، يدمجها مع المحلي (سجلات Firebase تتقدّم، الأحدث أولاً)، ويرفع
  أي عنصر محلي لسا ما وصل للقاعدة.
- القاعدة تقبل قراءة/كتابة بدون مصادقة حالياً (تحقق منها بـ curl). إذا شُدّدت
  القواعد لاحقاً لازم إضافة توكن/مصادقة في `FirebaseReels`.
- عنوان القاعدة: `BuildConfig.DEFAULT_FIREBASE_DB` ويمكن تجاوزه بـ
  `pigeon.firebaseDb` في `local.properties` وقت البناء فقط.

## 7. API البروفايل (مساران + سبب فشل ظاهر)

التطبيق يحاول مصدرين بالترتيب، ويستخدم أول واحد ينجح:

1. **Instagram Graph API مباشرة** بتوكن مدمج في البناء.
2. **بروكسي API خارجي على Cloudflare Workers** (`DEFAULT_API_ENDPOINT`، افتراضياً
   `https://brainrot-pigeon-api.brainrotpigeon.workers.dev/`) — يُستخدم لو حجبت شبكة
   الهاتف `graph.facebook.com` أو انتهت مهلتها.

إذا فشل الاثنان تُسجَّل الرسالة في logcat بوسم `PigeonProfile`، وتظهر تحت البايو
سطر صغير: «تعذّر تحديث البيانات من إنستغرام: <السبب>» بدل ما تُعرض الأرقام
الاحتياطية وكأنها حقيقية. إعادة المحاولة كل 5 ثوانٍ عند الفشل، و5 ثوانٍ عند النجاح،
مع تحديث فوري عند رجوع التطبيق للواجهة.

لا توجد أي أرقام افتراضية في `Profile` (العدّادات `Int?` وافتراضها `null`): قبل وصول
أول بيانات تظهر أشرطة Skeleton لامعة مكان الأرقام، وبعد خطأ تظهر `—`. مفتاح الكاش
`profile_json_v2` حتى تُهمل أي نسخة قديمة كانت تحمل الأرقام الافتراضية.

الطلبات المتزامنة (`OkHttp.execute()`) تُنفَّذ داخل `withContext(Dispatchers.IO)`، لأن
`viewModelScope` يعمل على الـ main thread؛ تشغيلها هناك كان يرمي
`NetworkOnMainThreadException` ويظهر كخطأ تحديث.

الطلب المباشر:

```
GET https://graph.facebook.com/v27.0/{IG_USER_ID}
    ?fields=id,username,name,profile_picture_url,biography,followers_count,follows_count,media_count
    &access_token={ACCESS_TOKEN}
```

- `IG_USER_ID` ثابت `17841438848736931`، ويُمكن تجاوزه بـ `pigeon.igUserId`
  في `local.properties` وقت البناء فقط.
- `ACCESS_TOKEN` مدمج في الـ APK (`BuildConfig.DEFAULT_IG_ACCESS_TOKEN`) عبر
  `pigeon.igAccessToken` أو القيمة الافتراضية في `build.gradle.kts`؛ لا توجد
  شاشة إعدادات ولا يمكن تعديله من داخل التطبيق.
- الحقول المقروءة: `username`, `biography`, `media_count`, `followers_count`,
  `follows_count`, `profile_picture_url`.
- عند 401/403 أو رمز الخطأ 190 من المصدرين معاً يتوقف التحديث التلقائي،
  ويُعاد التفعيل تلقائياً عند كل عودة للتطبيق (إن كان التوكن ما زال صالحاً).
- `pigeon.apiEndpoint` في `local.properties` يتجاوز عنوان البروكسي وقت البناء فقط.

## 8. ما الذي تم التحقق منه فعلياً وما لم يُتحقق

تم التحقق بشكل ساكن فقط (بدون Android SDK أو Gradle أو جهاز على هذا الكمبيوتر):

- صحة XML لكل ملفات الموارد والمانيفست.
- تطابق أسماء الأنشطة في المانيفست مع ملفات Kotlin.
- وجود كل مفاتيح `R.string` و`R.drawable` المستخدمة.
- توازن الأقواس في ملفات Kotlin، وخلو المشروع من WebView.

**لم يتم** تنفيذ أي مما يلي، ولا يمكن اعتباره ناجحاً حتى يُجرَّب:

- `gradle assembleDebug` / `bundleRelease` (سيحدث على GitHub Actions).
- `ShareParserTest` (JVM، سيُشغَّل على CI).
- تثبيت على هاتف أو `adb logcat`.
- المشاركة الفعلية من تطبيق إنستغرام/TikTok (البوب-أب فوق إنستغرام يحتاج تجربة على الجهاز).
- شبكة الريلز والنافذة الطافية بصرياً على جهاز (لا محاكي ولا هاتف هنا).
- مزامنة Firebase من داخل التطبيق (تم التحقق من REST فقط بـ curl: PUT/GET/DELETE ناجحة).

بعد أول تشغيل لـ Actions، راجع سجل المهمة؛ إذا فشل البناء سيكون الخطأ في السجل نفسه.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun configValue(name: String, fallback: String): String =
    (localProperties.getProperty(name) ?: providers.gradleProperty(name).orNull ?: fallback)
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile")
    ?.let { file(it).exists() } == true

android {
    namespace = "com.brainrot.pigeon"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.brainrot.pigeon"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "1.8.1"

        buildConfigField(
            "String",
            "DEFAULT_IG_USER_ID",
            "\"${configValue("pigeon.igUserId", "17841438848736931")}\""
        )
        // توكن مدمج ثابت: التطبيق شخصي ولا توجد شاشة إعدادات لتعديله.
        buildConfigField(
            "String",
            "DEFAULT_IG_ACCESS_TOKEN",
            "\"${configValue("pigeon.igAccessToken", "EAAXjGpto53wBTMVsnQQjeqVKWIWJAHfYmP5ZCaER1rsBNPBTK6ZCeNYucqtRP7ASlX8fWNpZBlrO8S1BxvxdBfnm1Uh77lLfCb3WfTft9qLWTgqYeVHPXfILry76ZAydMnijMCoWIqPuRq4DeU1HoMZBoJ4BlDAympi76NQURbegHL6z6nL8CHjINNYRiJEG7WZAitoK2UHQ3STPAdiUZAHgcMTo3u1OgPZBiVqMGVZAA4HNncrf1YgZDZD")}\""
        )
        // بروكسي API خارجي على Cloudflare Workers: مسار بديل لو حجب الهاتف graph.facebook.com.
        buildConfigField(
            "String",
            "DEFAULT_API_ENDPOINT",
            "\"${configValue("pigeon.apiEndpoint", "https://brainrot-pigeon-api.brainrotpigeon.workers.dev/")}\""
        )
        // Pigeon Reels API على Cloudflare: ينزّل الريل ويخزنه في R2 ويقدّمه للتحميل.
        buildConfigField(
            "String",
            "DEFAULT_ARCHIVE_ENDPOINT",
            "\"${configValue("pigeon.archiveEndpoint", "https://brainrot-pigeon-reels.brainrotpigeon.workers.dev")}\""
        )
        // اختياري: يطابق السر API_TOKEN في الـWorker؛ اتركه فاضياً إذا ما ضبطته.
        buildConfigField(
            "String",
            "DEFAULT_ARCHIVE_TOKEN",
            "\"${configValue("pigeon.archiveToken", "")}\""
        )
        // قاعدة بيانات الريلز المجدولة (Firebase Realtime Database) — قراءة/كتابة REST.
        buildConfigField(
            "String",
            "DEFAULT_FIREBASE_DB",
            "\"${configValue("pigeon.firebaseDb", "https://brainrotpigeon67-default-rtdb.europe-west1.firebasedatabase.app")}\""
        )
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}

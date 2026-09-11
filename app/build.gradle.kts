plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pos.scanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pos.scanner"
        minSdk = 23
        targetSdk = 34
        versionCode = 40
        versionName = "1.17"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 🆕 v1.17 — توقيعٌ ثابتٌ لكلّ بناء: بلا هذا، كلّ بناءٍ (من أيّ جهاز/بيئة) يُنتج
    //   شهادةً عشوائيّةً مختلفة، فيرفض أندرويد تحديثَ التطبيق فوق النسخة السابقة
    //   (يطلب إلغاء تثبيتٍ كاملاً كلَّ مرّة — هذا بالضبط ما كان يحدث). الملفّ
    //   alawael-release.keystore مرفقٌ فى هذا المجلّد نفسه؛ طالما استُعمل هو نفسُه
    //   فى كلّ بناءٍ قادم، تُحدَّث كلُّ نسخةٍ فوق التى قبلها بلا إلغاء تثبيت أبداً.
    signingConfigs {
        create("release") {
            storeFile = file("alawael-release.keystore")
            storePassword = "Alawael@2026Key"
            keyAlias = "alawael"
            keyPassword = "Alawael@2026Key"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // CameraX — الماسح
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Google ML Kit Barcode Scanning (Offline / Fast)
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    // OkHttp — إرسال فوري محليّ
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 🆕 v1.12 — تسجيل الدخول بالبصمة (QR + رمز جهاز): BiometricPrompt يحرس
    //   رمزَ الجهاز، وEncryptedSharedPreferences يخزّنه مشفَّراً على القرص (Android Keystore).
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}

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
        versionCode = 2
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    // نظام الأوائل — واجهة WebView سريعة تفتح نظام jawwal مباشرة.
    // WebView جزءٌ من أندرويد نفسه: لا حاجة لمكتبات كاميرا خارجيّة — النظام يمسح الباركود بنفسه.
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // WebKit للتحكّم المتقدّم بالـWebView (تفعيل السياق الآمن للكاميرا على http)
    implementation("androidx.webkit:webkit:1.10.0")
}

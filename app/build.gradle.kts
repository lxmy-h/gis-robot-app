plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.robotctrl.web"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.robotctrl.web"
        minSdk = 24
        targetSdk = 34
        versionCode = 21
        versionName = "2.1"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// 刻意不引入任何第三方依赖：只用平台 API + WebView，
// 这样依赖最少、体积最小，也不会被 AppCompat / AndroidX 的版本问题卡住。
dependencies {
}

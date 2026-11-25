plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zero.engine"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.developer.myzerogame"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

dependencies {
    // 高性能 QuickJS 引擎（ES6+ / 16KB 页大小对齐）
    implementation("wang.harlon.quickjs:wrapper-android:3.2.3")
}

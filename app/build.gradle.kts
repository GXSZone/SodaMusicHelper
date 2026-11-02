plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.sodamusic.helper"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // OpenCV 只保留真机 ABI，避免 4 个 ABI 的 so 全打进包
        ndk { abiFilters += listOf("arm64-v8a") }
        applicationId = "com.sodamusic.helper"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        disable += "NotificationPermission"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.google.material)
    implementation(libs.kotlinx.coroutines.android)
    // 已不提供 OCR：assists 的 MLKit 中文识别（约 40MB）不进包
    implementation(libs.assists.base) {
        exclude(group = "com.google.mlkit")
    }
    // OpenCV 模板匹配：仅调试包使用（release 不带，省 33MB 原生库）
    debugImplementation(libs.assists.opcv)
}
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Hikvision SDK مرخّص ولا يمكن توزيعه مع المشروع: ضع HCNetSDK.jar و PlayerSDK.jar في app/libs
// و ملفات .so في app/src/main/jniLibs/<abi>/ (أو استخدم scripts/install_hik_sdk.sh).
// عند غيابها يُبنى التطبيق بمصدر "غير متاح" صريح بدل أن يفشل البناء.
val hasHik = file("libs/HCNetSDK.jar").exists() && file("libs/PlayerSDK.jar").exists()

android {
    namespace = "com.smartwater.monitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.smartwater.monitor"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildConfigField("boolean", "HAS_HIK_SDK", hasHik.toString())
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug") // للتجربة؛ استبدله بتوقيعك للنشر
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging { jniLibs { useLegacyPackaging = true } } // Hikvision يحمّل مكوّناته من مجلد المكتبات

    sourceSets {
        getByName("main") {
            java.srcDir(if (hasHik) "src/hik/java" else "src/nohik/java")
        }
    }
}

dependencies {
    implementation("org.opencv:opencv:4.9.0")
    // libVLC: مشغّل RTSP متسامح مع Hikvision (H.265 بلا fmtp) — نفس ما اشتغل في RtspPersonTracker
    implementation("org.videolan.android:libvlc-all:3.6.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    if (hasHik) implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}

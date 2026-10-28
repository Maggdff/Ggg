plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.smartwater.monitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.smartwater.monitor"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

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

    packaging {
        jniLibs {
            useLegacyPackaging = true // libVLC يحمّل مكوّناته من مجلد المكتبات
            // libVLC و OpenCV كلاهما يشحن libc++_shared.so نفسها؛ نأخذ نسخة واحدة بدل فشل mergeDebugNativeLibs
            pickFirsts += listOf("**/libc++_shared.so")
        }
    }
}

dependencies {
    implementation("org.opencv:opencv:4.9.0")
    // libVLC: مشغّل RTSP متسامح مع Hikvision (H.265 بلا fmtp) — نفس ما اشتغل في RtspPersonTracker
    implementation("org.videolan.android:libvlc-all:3.6.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
}

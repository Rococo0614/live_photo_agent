plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vivo.lpa"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.vivo.lpa"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-probe"
        ndk {
            // 两台真机都是 arm64-v8a，只打这一个 ABI，包体减半
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
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

    packaging {
        jniLibs {
            // 两个 SDK 的 .so 若有同名，取第一个，避免打包冲突
            pickFirsts += "**/*.so"
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    // vivo 两个本地 SDK
    implementation(fileTree("libs") { include("*.aar") })

    // AAR 反编译可见依赖: androidx.annotation / collection / core / appcompat
    // videoeditorsdk 的 VMEditor 初始化路径依赖 Gson（AAR 未打包、未声明 POM）
    implementation("com.google.code.gson:gson:2.11.0")
    // mediaeffectsdk 的 TimelineMotionEffect 渲染路径依赖腾讯 PAG（同样未声明）
    implementation("com.tencent.tav:libpag:4.2.41")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
}

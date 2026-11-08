plugins {
    alias(libs.plugins.android.application)
}

/**
 * 独立的语音识别探针，和主 App 完全隔离：
 * 包名不同（com.bistu.speechprobe），可以同时装着，不会覆盖正式版、不碰它的数据。
 * 不用 Compose / Kotlin，纯 Java + 系统控件，包体几十 KB。
 */
android {
    namespace = "com.bistu.speechprobe"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.bistu.speechprobe"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

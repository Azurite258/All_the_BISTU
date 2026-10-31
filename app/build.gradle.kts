plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.bistu.allthebistu"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.bistu.allthebistu"
        minSdk = 33
        targetSdk = 37
        versionCode = 52
        versionName = "0.20-d"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                // 注意：不要把 kotlin.** 放进 packageScope。
                // kotlin-stdlib 的内部类（如 kotlin.sequences.d）被混淆后，
                // 会破坏类继承关系，运行时抛 IllegalAccessError 直接闪退。
                packageScope = setOf("androidx.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // JSON 解析
    implementation(libs.kotlinx.serialization.json)

    // 网络请求（用 Cookie 调教务系统接口）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.webkit:webkit:1.12.1")

    // SM2 国密加密（学校登录用）
    implementation("com.tencent.kona:kona-crypto:1.0.14")
    implementation("com.tencent.kona:kona-provider:1.0.14")

    // 本地数据库（缓存课表）
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 偏好存储
    implementation(libs.androidx.datastore.preferences)

    // 后台定时任务：课程提醒的每日兜底重排
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    testImplementation(libs.junit)
    // JVM 单元测试需要真实的 org.json 实现（Android SDK 里只有 stub，调用即抛异常）
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
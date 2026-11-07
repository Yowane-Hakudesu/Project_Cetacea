plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.whalechan.agent"
    compileSdk = 35
    // 显式锁定 build-tools 版本，避免 AGP 默认值（34.0.0）与已安装版本不一致
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.whalechan.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.0"

        // 只在真机上运行：无障碍 / 通知监听都依赖真实系统环境
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 使用工程内自带的 debug keystore，避免依赖 ~/.android/debug.keystore
    // （密码是 Android 默认调试密码，仅用于 debug 构建，不可用于发布）
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
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

    buildFeatures {
        buildConfig = false
    }

    lint {
        // 无障碍 API 在部分版本标记为 deprecated，但不影响功能
        abortOnError = false
    }
}

dependencies {
    // 两端共享的协议与 WebSocket 客户端（伴侣端也依赖它）
    implementation(project(":protocol"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 结构化执行日志（Room）
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // 手机端 WebSocket 服务端（伴侣端客户端在 :protocol 里）
    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    testImplementation("junit:junit:4.13.2")
    // 纯 JVM 单测里用真实 org.json 实现，替代 Android 运行时的那份
    testImplementation("org.json:json:20240303")
}

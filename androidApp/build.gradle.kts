plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "cn.dumate.a6studio.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.dumate.a6studio"
        minSdk = 26
        targetSdk = 35
        // versionCode 必须随 versionName 一起涨，否则覆盖安装会被系统当成同一版。
        versionCode = 3
        versionName = providers.gradleProperty("a6studio.version").get()
    }

    // 固定的 debug 签名。默认的 ~/.android/debug.keystore 每台机器、每个 CI runner 都是新生成的，
    // 于是同一个版本号的 APK 签名各不相同：装过上一版的人无法覆盖升级（INSTALL_FAILED_UPDATE_
    // INCOMPATIBLE），每跑一次 CI 就要先卸载。这里放一把公开的、只用于 debug 的密钥，本地与 CI
    // 签出来的包才能互换升级。**它不是发布密钥**，正式上架必须换成自己的 release key。
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("tools/debug.keystore")
            storePassword = "a6studio"
            keyAlias = "a6studio"
            keyPassword = "a6studio"
        }
    }

    buildFeatures { compose = true }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    // `by viewModels()`：状态放进 ViewModel，旋转屏幕不再重建 StudioState
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("io.ktor:ktor-client-okhttp:3.0.3")
}
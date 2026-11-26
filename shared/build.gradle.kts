import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm()

    sourceSets {
        // Everything that can use the JVM-only Rhino engine (Android + desktop).
        val commonMain by getting
        val jvmAndroidMain by creating { dependsOn(commonMain) }
        val androidMain by getting { dependsOn(jvmAndroidMain) }
        val jvmMain by getting { dependsOn(jvmAndroidMain) }

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // api: the platform hosts wire in an HttpClient engine themselves.
            api(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmAndroidMain.dependencies {
            implementation(libs.rhino)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(compose.desktop.currentOs)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.activity.compose)
        }
    }
}

// LiveMcpTest 靠环境变量连真机。Gradle 默认不把环境变量当输入，换一台手机（或换令牌）后
// 任务仍是 UP-TO-DATE，跑的还是上一台的日志——实测踩过。声明成输入后每次都会真的重跑。
tasks.withType<Test>().configureEach {
    inputs.property("A6_MCP_URL", providers.environmentVariable("A6_MCP_URL").orElse(""))
    inputs.property("A6_MCP_TOKEN", providers.environmentVariable("A6_MCP_TOKEN").orElse(""))
}

android {
    namespace = "cn.dumate.a6studio.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
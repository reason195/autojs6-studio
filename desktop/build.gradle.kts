import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm()
    sourceSets {
        jvmMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.cio)
        }
    }
}

compose.desktop {
    application {
        mainClass = "cn.dumate.a6studio.desktop.MainKt"

        // The Compose plugin already writes -Dskiko.library.path, the resources dir and the
        // swing globals into the packaged .cfg. Listing them here again only duplicated them,
        // and the resources dir ended up mangled into `$APPDIR` + `esources` (a stray `\r`).
        // Keep just the heap size.
        jvmArgs += listOf("-Xmx2048m")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "AutoJs6 Studio"
            // jpackage 与版本号的关系：MSI 文件名和升级判断都来自这里，必须与 Android 一致地取自
            // gradle.properties，否则两处手改迟早对不上。
            packageVersion = providers.gradleProperty("a6studio.version").get()
            // ASCII only: jpackage puts this into the MSI summary info, and WiX 3.x then fails
            // with a bare `light.exe ... exited with 311 code` if the text is not Latin
            // (verified on JDK 21 + WiX 3.11: Chinese description fails, ASCII succeeds).
            // The Chinese product description lives in the app UI and the release notes.
            description = "AutoJs6 visual scripting studio"
            // Shown in the MSI/apps list; jpackage prints "Unknown" otherwise.
            vendor = "reason195"
            copyright = "MPL-2.0"

            windows {
                // Stable GUIDs so an upgrade replaces the previous install instead of stacking.
                upgradeUuid = "7B690782-9016-39CF-9852-3AD1D1EEAFB8"
                menuGroup = "AutoJs6 Studio"
                shortcut = true
                dirChooser = true
                perUserInstall = false
            }
        }
    }
}
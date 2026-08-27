plugins {
    id("phoebe.compose.library")
    id("phoebe.metro")
    kotlin("plugin.serialization")
}

val javaFxClassifier = when {
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) &&
        System.getProperty("os.arch") == "aarch64" -> "mac-aarch64"
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "mac"
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "win"
    System.getProperty("os.arch") == "aarch64" -> "linux-aarch64"
    else -> "linux"
}

val composeDesktopTarget = when {
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) &&
        System.getProperty("os.arch") == "aarch64" -> "macos-arm64"
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "macos-x64"
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) &&
        System.getProperty("os.arch") == "aarch64" -> "windows-arm64"
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows-x64"
    System.getProperty("os.arch") == "aarch64" -> "linux-arm64"
    else -> "linux-x64"
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":core:platform"))
                implementation(project(":data:database"))
                implementation(project(":data:catalog"))
                implementation(project(":data:listenbrainz"))
                implementation(project(":data:local-media"))
                implementation(project(":data:network"))
                implementation(project(":data:providers:plex"))
                implementation(project(":data:providers:jellyfin"))
                implementation(project(":data:providers:subsonic"))
                implementation(project(":data:session"))
                implementation(project(":data:settings"))
                implementation(project(":domain"))
                implementation(libs.ktor.client.core)
                implementation(libs.serialization.json)
            }
        }
        commonTest {
            kotlin.srcDir("$rootDir/test-support/network/kotlin")
            kotlin.srcDir("$rootDir/test-support/credentials/kotlin")
            kotlin.srcDir("$rootDir/test-support/listenbrainz/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.coroutines.test)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.client.mock)
                implementation(libs.ktor.serialization.json)
            }
        }
        named("androidHostTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.junit)
                implementation(libs.androidx.test.core)
                implementation(libs.androidx.test.runner)
                implementation(libs.coroutines.test)
                implementation(libs.robolectric)
            }
        }
        desktopTest {
            kotlin.srcDir("$rootDir/test-support/database/desktop/kotlin")
            kotlin.srcDir("$rootDir/test-support/platform/desktop/kotlin")
            resources.srcDir("$rootDir/composeApp/src/commonTest/resources")
            dependencies {
                implementation(libs.junit)
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
        androidMain {
            dependencies {
                implementation(libs.androidx.media3.cast)
                implementation(libs.androidx.media3.exoplayer)
                implementation(libs.androidx.media3.exoplayer.hls)
                implementation(libs.androidx.media3.session)
                implementation(libs.ktor.client.okhttp)
            }
        }
        desktopMain {
            dependencies {
                implementation("org.jetbrains.compose.desktop:desktop-jvm-$composeDesktopTarget:${libs.versions.compose.get()}")
                implementation(libs.jnativehook)
                implementation(libs.jna)
                implementation(libs.jna.platform)
                implementation(libs.coroutines.swing)
                implementation(libs.ktor.client.cio)
                implementation("org.openjfx:javafx-base:${libs.versions.javafx.get()}:$javaFxClassifier")
                implementation("org.openjfx:javafx-graphics:${libs.versions.javafx.get()}:$javaFxClassifier")
                implementation("org.openjfx:javafx-media:${libs.versions.javafx.get()}:$javaFxClassifier")
                implementation("org.openjfx:javafx-swing:${libs.versions.javafx.get()}:$javaFxClassifier")
                implementation(libs.soundlibs.mp3spi)
                implementation(libs.soundlibs.vorbisspi)
                implementation(libs.jflac.codec)
                implementation(libs.chromecast.java.api.v2)
            }
        }
        wasmJsMain {
            dependencies {
                implementation(libs.kotlinx.browser)
                implementation(libs.ktor.client.js)
            }
        }
        iosMain {
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
    }
}

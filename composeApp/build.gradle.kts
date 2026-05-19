import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import java.io.File
import java.time.Duration

val phoebeVersionName = providers.gradleProperty("phoebe.versionName")
    .orElse(providers.environmentVariable("PHOEBE_VERSION_NAME"))
    .orElse("1.0.0")

val phoebeVersionCode = providers.gradleProperty("phoebe.versionCode")
    .orElse(providers.environmentVariable("PHOEBE_VERSION_CODE"))
    .map(String::toInt)
    .orElse(1)

val phoebeJpackagePackageVersion = phoebeVersionName.map { version ->
    val parts = version.split(".")
    val major = parts.firstOrNull()?.toIntOrNull()
    if (major != null && parts.size == 3) {
        // Early native packages need a positive, monotonic jpackage version while
        // the public app version remains semver.
        "${major + 1}.${parts[1]}.${parts[2]}"
    } else {
        version
    }
}

val phoebeDebugDistribution = providers.gradleProperty("phoebe.debugDistribution")
    .orElse(providers.environmentVariable("PHOEBE_DEBUG_DISTRIBUTION"))
    .map(String::toBoolean)
    .orElse(false)

fun providerValue(name: String, envName: String): String? =
    providers.gradleProperty(name).orElse(providers.environmentVariable(envName)).orNull

fun String.asJpackageMacSigningUserName(): String =
    removePrefix("Developer ID Application: ")
        .removePrefix("3rd Party Mac Developer Application: ")

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

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.sentryJvm)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    androidTarget()

    jvm("desktop")

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "phoebe.js"
            }
        }
        binaries.executable()
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }


    sourceSets {
        val commonTest by getting
        val desktopMain by getting
        val desktopTest by getting
        val wasmJsMain by getting
        val androidUnitTest by getting
        val androidInstrumentedTest by getting {
            kotlin.srcDir("src/commonTest/kotlin")
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.components.resources)
            implementation(libs.navigation3.ui)
            implementation(libs.atomicfu)
            implementation(libs.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.serialization.json)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.async.extensions)
            implementation(libs.sqldelight.coroutines.extensions)
            implementation(libs.sqldelight.primitive.adapters)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.coroutines.test)
        }
        desktopTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.junit)
            implementation(libs.coroutines.test)
            implementation(libs.compose.ui.test.junit4)
            implementation(libs.roborazzi.compose.desktop)
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.serialization.json)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.sqldelight.async.extensions)
            implementation(libs.sqldelight.coroutines.extensions)
            implementation(libs.sqldelight.primitive.adapters)
        }
        androidInstrumentedTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.junit)
            implementation(libs.coroutines.test)
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.rules)
            implementation(libs.androidx.test.ext.junit)
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.activity.compose)
            implementation("androidx.compose.ui:ui-test-junit4")
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.serialization.json)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.sqldelight.async.extensions)
            implementation(libs.sqldelight.coroutines.extensions)
            implementation(libs.sqldelight.primitive.adapters)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.runtime)
        }
        androidUnitTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.junit)
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.rules)
            implementation(libs.androidx.test.ext.junit)
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.activity.compose)
            implementation("androidx.compose.ui:ui-test-junit4")
            implementation("androidx.compose.ui:ui-test-manifest")
            implementation(libs.robolectric)
            implementation(libs.roborazzi.compose)
            implementation(libs.roborazzi.core)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.runtime)
        }
        wasmJsTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.fragment)
            implementation(libs.androidx.documentfile)
            implementation(libs.androidx.media3.cast)
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.session)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.sentry.kotlin.multiplatform)
        }
        desktopMain.dependencies {
            implementation("org.jetbrains.compose.desktop:desktop-jvm-$composeDesktopTarget:${libs.versions.compose.get()}")
            implementation(libs.jnativehook)
            implementation(libs.jna)
            implementation(libs.coroutines.swing)
            implementation(libs.jaudiotagger)
            implementation(libs.ktor.client.cio)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.sentry.kotlin.multiplatform)
            implementation("org.openjfx:javafx-base:${libs.versions.javafx.get()}:$javaFxClassifier")
            implementation("org.openjfx:javafx-graphics:${libs.versions.javafx.get()}:$javaFxClassifier")
            implementation("org.openjfx:javafx-media:${libs.versions.javafx.get()}:$javaFxClassifier")
            implementation("org.openjfx:javafx-swing:${libs.versions.javafx.get()}:$javaFxClassifier")
            // javax.sound.sampled SPI: FLAC / Ogg Vorbis / MP3 decoded streams (JavaFX Media does not support these).
            implementation(libs.soundlibs.mp3spi)
            implementation(libs.soundlibs.vorbisspi)
            implementation(libs.jflac.codec)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
            implementation(libs.sqldelight.web.worker.driver)
            implementation(npm("@cashapp/sqldelight-sqljs-worker", libs.versions.sqldelight.get()))
            implementation(npm("sql.js", "1.8.0"))
            implementation(devNpm("copy-webpack-plugin", "9.1.0"))
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
    }
}

sqldelight {
    databases {
        create("PhoebeDatabase") {
            packageName.set("com.phoebe.app.db")
            generateAsync.set(true)
        }
    }
}

roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/screenshotTest/roborazzi"))
}

sentry {
    includeSourceContext = true
    org = "personal-0mr"
    projectName = "phoebe"
    authToken = System.getenv("SENTRY_AUTH_TOKEN")
}

android {
    namespace = "com.phoebe.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.phoebe.app"
        minSdk = 26
        targetSdk = 36
        versionCode = phoebeVersionCode.get()
        versionName = phoebeVersionName.get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.systemProperties["robolectric.pixelCopyRenderMode"] = "hardware"
                it.maxHeapSize = "4096m"
            }
        }
    }

    val releaseStoreFile = providerValue("phoebe.android.signing.storeFile", "PHOEBE_ANDROID_SIGNING_STORE_FILE")
    val releaseStorePassword = providerValue("phoebe.android.signing.storePassword", "PHOEBE_ANDROID_SIGNING_STORE_PASSWORD")
    val releaseKeyAlias = providerValue("phoebe.android.signing.keyAlias", "PHOEBE_ANDROID_SIGNING_KEY_ALIAS")
    val releaseKeyPassword = providerValue("phoebe.android.signing.keyPassword", "PHOEBE_ANDROID_SIGNING_KEY_PASSWORD")

    if (
        releaseStoreFile != null &&
        releaseStorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null
    ) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.phoebe.app.MainKt"
        jvmArgs += listOf(
            "-Xms32m",
            "-Xmx256m",
            "-XX:MinHeapFreeRatio=5",
            "-XX:MaxHeapFreeRatio=20",
            "-XX:+UseStringDeduplication",
            "-Dskiko.gpu.resourceCacheLimit=64M",
        )
        if (System.getProperty("os.name").lowercase().contains("mac")) {
            val mediaKeysDylibPath =
                layout.buildDirectory.get().asFile.resolve("native/macos/libPhoebeMediaKeys.dylib").absolutePath
            jvmArgs += listOf("-Dphoebe.mediakeys.lib=$mediaKeysDylibPath")
        }
        buildTypes.release.proguard {
            configurationFiles.from(project.file("desktop-release.pro"))
        }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            modules("java.instrument", "java.management", "java.net.http", "java.sql", "jdk.jfr", "jdk.unsupported")
            packageName = if (phoebeDebugDistribution.get()) "Phoebe Debug" else "Phoebe"
            packageVersion = phoebeVersionName.get()
            val iconsDir = project.layout.projectDirectory.dir(
                if (phoebeDebugDistribution.get()) {
                    "src/desktopMain/resources/icons-debug"
                } else {
                    "src/desktopMain/resources/icons"
                },
            )
            macOS {
                bundleID = if (phoebeDebugDistribution.get()) "com.phoebe.app.debug" else "com.phoebe.app"
                packageVersion = phoebeJpackagePackageVersion.get()
                iconFile.set(iconsDir.file("icon.icns").asFile)
                signing {
                    identity.set(
                        providers.gradleProperty("compose.desktop.mac.signing.identity")
                            .map(String::asJpackageMacSigningUserName)
                    )
                }
            }
            windows {
                iconFile.set(iconsDir.file("icon.ico").asFile)
                msiPackageVersion = phoebeJpackagePackageVersion.get()
                upgradeUuid = if (phoebeDebugDistribution.get()) {
                    "17D3846B-2D54-4AB4-A692-B1A3889D6D62"
                } else {
                    "2C1E8421-2A6D-42AA-8C59-C82BE282E92F"
                }
            }
            linux {
                iconFile.set(iconsDir.file("icon.png").asFile)
            }
        }
    }
}

dependencies {
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val compileMacMediaKeysNative = tasks.register<Exec>("compileMacMediaKeysNative") {
    onlyIf { System.getProperty("os.name").lowercase().contains("mac") }
    val outDir = layout.buildDirectory.dir("native/macos").get().asFile
    val outFile = File(outDir, "libPhoebeMediaKeys.dylib")
    val src = layout.projectDirectory.file("native/macos/MediaKeysBridge.m").asFile
    inputs.file(src)
    outputs.file(outFile)
    doFirst { outDir.mkdirs() }
    val javaHome = System.getProperty("java.home") ?: error("java.home is not set")
    commandLine(
        "clang",
        "-dynamiclib",
        "-fobjc-arc",
        "-framework",
        "Foundation",
        "-framework",
        "MediaPlayer",
        "-I$javaHome/include",
        "-I$javaHome/include/darwin",
        "-mmacosx-version-min=11.0",
        "-o",
        outFile.absolutePath,
        src.absolutePath,
    )
}

tasks.named("compileKotlinDesktop") { dependsOn(compileMacMediaKeysNative) }

val desktopDevRunTaskNames = setOf("run", "hotRunDesktop", "hotDevDesktop", "desktopRunHot")

tasks.withType<JavaExec>().configureEach {
    if (name !in desktopDevRunTaskNames) return@configureEach

    systemProperty("phoebe.debug", "true")
    val debugHome = File(System.getProperty("user.home"), ".phoebe-debug")
    systemProperty("phoebe.storage.root", debugHome.absolutePath)

    // Compose Desktop always passes -Xdock:icon for the production .icns; swap it for debug runs.
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        val debugDockIcon = layout.projectDirectory
            .file("src/desktopMain/resources/icons-debug/icon.icns")
            .asFile
        doFirst {
            jvmArgs = jvmArgs
                .filterNot { it.startsWith("-Xdock:icon=") }
                .plus("-Xdock:icon=${debugDockIcon.absolutePath}")
        }
    }
}

tasks.withType<Test>().configureEach {
    val requestedRoborazziTasks = gradle.startParameter.taskNames.map { it.substringAfterLast(":") }
    val requestedAnyRoborazzi = requestedRoborazziTasks.any {
        it == "recordRoborazzi" ||
            it == "verifyRoborazzi" ||
            it == "compareRoborazzi" ||
            it == "verifyAndRecordRoborazzi" ||
            it.startsWith("recordRoborazzi") ||
            it.startsWith("verifyRoborazzi") ||
            it.startsWith("compareRoborazzi") ||
            it.startsWith("verifyAndRecordRoborazzi")
    }

    if (name.contains("desktop", ignoreCase = true)) {
        systemProperty("phoebe.debug", "true")
    }
    if (requestedAnyRoborazzi) {
        timeout.set(Duration.ofMinutes(5))
        maxParallelForks = 1
    }
    if (requestedAnyRoborazzi && name == "testDebugUnitTest") {
        filter {
            includeTestsMatching("com.phoebe.app.PhoebeAndroid*ScreenshotTest")
        }
    }
    if (requestedAnyRoborazzi && name == "desktopTest") {
        filter {
            includeTestsMatching("com.phoebe.app.PhoebeDesktopScreenshotTest")
        }
    }
}

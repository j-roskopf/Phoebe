import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
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
    if (major != null && major <= 250 && parts.size == 3) {
        val minor = parts[1].toIntOrNull()
        val patch = parts[2].toIntOrNull()
        if (minor != null && minor <= 255 && patch != null && patch <= 65535) {
            "${major + 1}.$minor.$patch"
        } else {
            val code = phoebeVersionCode.get()
            "100.0.$code"
        }
    } else {
        val code = phoebeVersionCode.get()
        "100.0.$code"
    }
}

val phoebeDebugDistribution = providers.gradleProperty("phoebe.debugDistribution")
    .orElse(providers.environmentVariable("PHOEBE_DEBUG_DISTRIBUTION"))
    .map(String::toBoolean)
    .orElse(false)

val phoebeDesktopProguard = providers.gradleProperty("phoebe.desktopProguard")
    .orElse(providers.environmentVariable("PHOEBE_DESKTOP_PROGUARD"))
    .map(String::toBoolean)
    .orElse(false)

val phoebeRealAudioTests = providers.gradleProperty("phoebe.realAudioTests")
    .orElse(providers.environmentVariable("PHOEBE_REAL_AUDIO_TESTS"))
    .map(String::toBoolean)
    .orElse(false)

val desktopJavaLanguageVersion = JavaLanguageVersion.of(22)
val desktopJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(desktopJavaLanguageVersion)
}
val desktopJavaHome = desktopJavaLauncher.map { launcher ->
    launcher.metadata.installationPath.asFile.absolutePath
}
val desktopJavaExecutable = desktopJavaLauncher.map { launcher ->
    launcher.executablePath.asFile.absolutePath
}

fun providerValue(name: String, envName: String): String? =
    providers.gradleProperty(name).orElse(providers.environmentVariable(envName)).orNull

fun String.asJpackageMacSigningUserName(): String =
    removePrefix("Developer ID Application: ")
        .removePrefix("Developer ID Installer: ")
        .removePrefix("3rd Party Mac Developer Application: ")
        .removePrefix("3rd Party Mac Developer Installer: ")

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

fun usesJvmWithAarch64C1OsrBug(): Boolean {
    if (System.getProperty("os.arch") != "aarch64") return false
    val version = Runtime.version()
    return when (version.feature()) {
        17 -> version.update() < 16
        21 -> version.update() < 6
        else -> false
    }
}

val aarch64C1OsrWorkaroundJvmArgs = if (usesJvmWithAarch64C1OsrBug()) {
    // JDK-8310844/JDK-8320682: C1 OSR compilation can abort the VM with
    // "Field too big for insn" on older AArch64 JDK 17/21 builds.
    listOf("-XX:-UseOnStackReplacement")
} else {
    emptyList()
}

fun usesLinuxX64SuperWordCrashProneJvm(): Boolean =
    System.getProperty("os.name").startsWith("Linux", ignoreCase = true) &&
        System.getProperty("os.arch") == "amd64" &&
        Runtime.version().feature() == 17

val linuxX64SuperWordWorkaroundJvmArgs = if (usesLinuxX64SuperWordCrashProneJvm()) {
    // Temurin 17 can crash Linux x64 desktop UI tests with
    // "scalar-to-vector conversion failed" during C2 vectorization.
    listOf("-XX:-UseSuperWord")
} else {
    emptyList()
}

fun windowsSkikoJvmArgs(): List<String> {
    if (!System.getProperty("os.name").orEmpty().lowercase().contains("win")) return emptyList()
    val renderApi = System.getenv("PHOEBE_SKIKO_RENDER_API")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: System.getProperty("phoebe.skiko.renderApi")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    return when (renderApi?.uppercase()) {
        "OPENGL", "DIRECT3D", "SOFTWARE", "SOFTWARE_COMPAT" -> listOf("-Dskiko.renderApi=${renderApi.uppercase()}")
        "ANGLE", null -> listOf("-Dskiko.rendering.angle.enabled=true")
        else -> listOf("-Dskiko.rendering.angle.enabled=true")
    }
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.sentryJvm)
}

kotlin {
    jvmToolchain(22)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    android {
        namespace = "com.phoebe.app"
        compileSdk = 36
        minSdk = 26
        androidResources {
            enable = true
        }
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        withHostTest {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            instrumentationRunnerArguments["phoebe.realAudioTests"] = phoebeRealAudioTests.get().toString()
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_22)
        }
    }

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
            binaryOption("bundleId", "com.joetr.phoebe.ComposeApp")
            isStatic = true
            export(project(":playback"))
            transitiveExport = true
        }
    }


    sourceSets {
        val commonMain by getting
        val commonTest by getting
        val desktopMain by getting
        val desktopTest by getting
        val wasmJsMain by getting
        val androidHostTest by getting {
            kotlin.srcDir("src/androidUnitTest/kotlin")
        }
        val androidDeviceTest by getting {
            kotlin.srcDir("src/androidInstrumentedTest/kotlin")
            resources.srcDir("src/commonTest/resources")
        }
        commonMain.dependencies {
            implementation(project(":core:platform"))
            implementation(project(":core:di"))
            implementation(project(":data:artwork"))
            implementation(project(":data:catalog"))
            implementation(project(":data:database"))
            implementation(project(":data:events"))
            implementation(project(":data:listenbrainz"))
            implementation(project(":data:local-media"))
            implementation(project(":data:lyrics"))
            implementation(project(":data:musicbrainz"))
            implementation(project(":data:network"))
            implementation(project(":data:play-history"))
            implementation(project(":data:playlists"))
            implementation(project(":data:providers:jellyfin"))
            implementation(project(":data:providers:musicassistant"))
            implementation(project(":data:providers:plex"))
            implementation(project(":data:providers:subsonic"))
            implementation(project(":data:session"))
            implementation(project(":data:settings"))
            implementation(project(":data:updates"))
            implementation(project(":domain"))
            implementation(project(":feature:auth"))
            implementation(project(":feature:collections"))
            implementation(project(":feature:details"))
            implementation(project(":feature:favorites"))
            implementation(project(":feature:history"))
            implementation(project(":feature:home"))
            implementation(project(":feature:library"))
            implementation(project(":feature:lyrics"))
            implementation(project(":feature:playback"))
            implementation(project(":feature:radio"))
            implementation(project(":feature:search"))
            implementation(project(":feature:settings"))
            implementation(project(":navigation"))
            api(project(":playback"))
            implementation(project(":ui:core"))
            implementation(project(":ui:media"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.components.resources)
            implementation(libs.jetbrains.navigation3.ui)
            implementation(libs.haze.blur)
            implementation(libs.coroutines.core)
            implementation(libs.lifecycle.viewmodel)
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
        androidDeviceTest.dependencies {
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
            implementation("androidx.compose.ui:ui-test-manifest")
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
        androidHostTest.dependencies {
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
            implementation(libs.androidx.media3.exoplayer.hls)
            implementation(libs.androidx.media3.session)
            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.sentry.kotlin.multiplatform)
        }
        desktopMain.dependencies {
            implementation("org.jetbrains.compose.desktop:desktop-jvm-$composeDesktopTarget:${libs.versions.compose.get()}")
            if (composeDesktopTarget.startsWith("windows")) {
                // ANGLE backend: more stable than raw D3D12/OpenGL-in-Swing on many Windows GPUs.
                implementation("org.jetbrains.skiko:skiko-awt-runtime-angle-$composeDesktopTarget:0.144.6")
            }
            implementation(libs.jnativehook)
            implementation(libs.jna)
            implementation(libs.jna.platform)
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
            implementation(libs.chromecast.java.api.v2)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
            implementation(libs.ktor.client.mock)
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

roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/screenshotTest/roborazzi"))
}

sentry {
    includeSourceContext = true
    org = "personal-0mr"
    projectName = "phoebe"
    authToken = System.getenv("SENTRY_AUTH_TOKEN")
}

private val macosLocalNetworkInfoPlistKeys = """
    <key>NSBonjourServices</key>
    <array>
        <string>_googlecast._tcp</string>
        <string>_CC1AD845._googlecast._tcp</string>
    </array>
    <key>NSLocalNetworkUsageDescription</key>
    <string>Phoebe uses the local network to discover and control Chromecast devices and connect to media servers on your home network.</string>
""".trimIndent()

val macMediaKeysAppResources = layout.buildDirectory.dir("generated/appResources")
val macMediaKeysResourceDirName = providers.provider {
    when (System.getProperty("os.arch")) {
        "aarch64" -> "macos-arm64"
        "x86_64", "amd64" -> "macos-x64"
        else -> "macos"
    }
}

compose.desktop {
    application {
        mainClass = "com.phoebe.app.MainKt"
        javaHome = desktopJavaHome.get()
        jvmArgs += listOf(
            "-Xms64m",
            "-Xmx768m",
            "-XX:MinHeapFreeRatio=5",
            "-XX:MaxHeapFreeRatio=20",
            "-XX:+UseStringDeduplication",
            "-Dskiko.gpu.resourceCacheLimit=64M",
        ) + aarch64C1OsrWorkaroundJvmArgs + windowsSkikoJvmArgs()
        if (System.getProperty("os.name").lowercase().contains("mac")) {
            val mediaKeysDylibPath =
                layout.buildDirectory.get().asFile.resolve("native/macos/libPhoebeMediaKeys.dylib").absolutePath
            jvmArgs += listOf(
                "-Dphoebe.mediakeys.lib=$mediaKeysDylibPath",
                "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
            )
        }
        buildTypes.release.proguard {
            isEnabled.set(phoebeDesktopProguard)
            configurationFiles.from(project.file("desktop-release.pro"))
        }
        nativeDistributions {
            appResourcesRootDir.set(macMediaKeysAppResources)
            targetFormats(TargetFormat.Dmg, TargetFormat.Pkg, TargetFormat.Msi, TargetFormat.Deb)
            modules(
                "java.desktop",
                "java.instrument",
                "java.logging",
                "java.management",
                "java.net.http",
                "java.sql",
                "java.xml",
                "jdk.httpserver",
                "jdk.jfr",
                "jdk.unsupported",
            )
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
                bundleID = if (phoebeDebugDistribution.get()) "com.joetr.phoebe.debug" else "com.joetr.phoebe"
                packageVersion = phoebeJpackagePackageVersion.get()
                iconFile.set(iconsDir.file("icon.icns").asFile)
                val macEntitlements = project.layout.projectDirectory.file("desktop/macos/Phoebe.entitlements")
                entitlementsFile.set(macEntitlements)
                runtimeEntitlementsFile.set(macEntitlements)
                infoPlist {
                    extraKeysRawXml = macosLocalNetworkInfoPlistKeys
                }
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

val compileMacMediaKeysNative = tasks.register<Exec>("compileMacMediaKeysNative") {
    onlyIf { System.getProperty("os.name").lowercase().contains("mac") }
    val outDir = layout.buildDirectory.dir("native/macos").get().asFile
    val outFile = File(outDir, "libPhoebeMediaKeys.dylib")
    val src = layout.projectDirectory.file("native/macos/MediaKeysBridge.m").asFile
    inputs.file(src)
    outputs.file(outFile)
    doFirst { outDir.mkdirs() }
    doFirst {
        val javaHome = desktopJavaHome.get()
        commandLine(
            "clang",
            "-dynamiclib",
            "-fobjc-arc",
            "-framework",
            "Foundation",
            "-framework",
            "AppKit",
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
}

val syncMacMediaKeyResources = tasks.register<Sync>("syncMacMediaKeyResources") {
    onlyIf { System.getProperty("os.name").lowercase().contains("mac") }
    dependsOn(compileMacMediaKeysNative)
    from(layout.buildDirectory.file("native/macos/libPhoebeMediaKeys.dylib"))
    into(macMediaKeysAppResources.map { it.dir(macMediaKeysResourceDirName.get()) })
}

tasks.named("compileKotlinDesktop") { dependsOn(compileMacMediaKeysNative) }
tasks.matching { it.name in setOf("prepareAppResources", "createDistributable", "runDistributable", "packageDmg", "packagePkg") }
    .configureEach {
        dependsOn(syncMacMediaKeyResources)
    }

val desktopDevRunTaskNames = setOf("run", "hotRunDesktop", "hotDevDesktop", "desktopRunHot")

tasks.withType<JavaExec>().configureEach {
    if (name !in desktopDevRunTaskNames) return@configureEach

    javaLauncher.set(desktopJavaLauncher)
    doFirst {
        setExecutable(desktopJavaExecutable.get())
    }
    systemProperty("phoebe.debug", "true")
    System.getProperty("phoebe.desktop.navigationPath")
        ?.takeIf { it.isNotBlank() }
        ?.let { systemProperty("phoebe.desktop.navigationPath", it) }
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
        javaLauncher.set(desktopJavaLauncher)
        systemProperty("phoebe.debug", "true")
        jvmArgs(linuxX64SuperWordWorkaroundJvmArgs)
    }
    systemProperty("phoebe.realAudioTests", phoebeRealAudioTests.get().toString())
    if (phoebeRealAudioTests.get() && name.contains("desktop", ignoreCase = true)) {
        maxParallelForks = 1
    }
    if (requestedAnyRoborazzi) {
        timeout.set(Duration.ofMinutes(5))
        maxParallelForks = 1
    }
    if (requestedAnyRoborazzi && name == "testAndroidHostTest") {
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

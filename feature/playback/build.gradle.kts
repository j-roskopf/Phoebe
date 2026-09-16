plugins {
    id("phoebe.feature")
    kotlin("plugin.serialization")
}

import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

val lwjglNatives = when {
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) &&
        System.getProperty("os.arch") == "aarch64" -> "natives-macos-arm64"
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "natives-macos"
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) &&
        (System.getProperty("os.arch") == "aarch64" || System.getProperty("os.arch") == "arm64") ->
        "natives-windows-arm64"
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "natives-windows"
    System.getProperty("os.arch") == "aarch64" || System.getProperty("os.arch") == "arm64" ->
        "natives-linux-arm64"
    else -> "natives-linux"
}

kotlin {
    applyDefaultHierarchyTemplate()

    targets.withType<KotlinNativeTarget>().configureEach {
        val installName = when (name) {
            "iosSimulatorArm64" -> "ios-sim"
            "iosArm64" -> "ios-device"
            else -> return@configureEach
        }
        val installDir = rootProject.layout.projectDirectory.dir("native/projectm/$installName")
        val includeDir = installDir.dir("include").asFile
        val libDir = installDir.dir("lib").asFile
        val staticLib = libDir.resolve("libprojectM-4.a")

        compilations.getByName("main").cinterops {
            val projectm by creating {
                defFile(project.file("src/iosMain/cinterop/projectm.def"))
                includeDirs(includeDir)
                compilerOpts(
                    "-I${includeDir.absolutePath}",
                    "-DPROJECTM_STATIC_DEFINE",
                )
            }
        }

        // Static lib search path for any native binary this module produces (tests).
        binaries.all {
            linkerOpts("-L${libDir.absolutePath}")
            linkTaskProvider.configure {
                doFirst {
                    check(staticLib.isFile) {
                        "Missing ${staticLib.absolutePath}. Run: ./scripts/build-projectm.sh $installName"
                    }
                }
            }
        }
    }

    sourceSets {
        val jniMain by creating {
            dependsOn(getByName("commonMain"))
        }

        commonMain {
            dependencies {
                implementation(project(":core:platform"))
                implementation(project(":data:listenbrainz"))
                implementation(project(":playback"))
                implementation(project(":ui:media"))
                implementation(libs.ktor.client.core)
                implementation(libs.serialization.json)
            }
        }
        desktopMain {
            dependsOn(jniMain)
            dependencies {
                implementation(libs.jnativehook)
                val lwjglVersion = libs.versions.lwjgl.get()
                implementation("org.lwjgl:lwjgl:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-opengl:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-jawt:$lwjglVersion")
                implementation("org.lwjgl:lwjgl:$lwjglVersion:$lwjglNatives")
                implementation("org.lwjgl:lwjgl-opengl:$lwjglVersion:$lwjglNatives")
                implementation("org.lwjglx:lwjgl3-awt:${libs.versions.lwjglAwt.get()}") {
                    exclude(group = "org.lwjgl")
                }
            }
        }
        androidMain {
            dependsOn(jniMain)
        }
        iosMain {
            // projectM cinterop registered on iosArm64 / iosSimulatorArm64 above.
        }
    }
}

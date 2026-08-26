plugins {
    id("phoebe.compose.library")
    kotlin("plugin.serialization")
}

val phoebeVersionName = providers.gradleProperty("phoebe.versionName")
    .orElse(providers.environmentVariable("PHOEBE_VERSION_NAME"))
    .orElse("1.0.0")

val phoebeVersionCode = providers.gradleProperty("phoebe.versionCode")
    .orElse(providers.environmentVariable("PHOEBE_VERSION_CODE"))
    .map(String::toInt)
    .orElse(1)

val phoebeBuildInfoOutput = layout.buildDirectory.dir("generated/phoebeBuildInfo/kotlin")
val generatePhoebeBuildInfo = tasks.register("generatePhoebeBuildInfo") {
    val outputDir = phoebeBuildInfoOutput
    inputs.property("versionName", phoebeVersionName)
    inputs.property("versionCode", phoebeVersionCode)
    inputs.property("githubOwner", "j-roskopf")
    inputs.property("githubRepo", "Phoebe")
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("com/phoebe/app/platform/PhoebeBuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package com.phoebe.app.platform

            object PhoebeBuildInfo {
                const val versionName: String = "${phoebeVersionName.get()}"
                const val versionCode: Int = ${phoebeVersionCode.get()}
                const val githubOwner: String = "j-roskopf"
                const val githubRepo: String = "Phoebe"
            }
            """.trimIndent() + "\n",
        )
    }
}

kotlin {
    sourceSets {
        commonMain {
            kotlin.srcDir(phoebeBuildInfoOutput)
            dependencies {
                implementation(project(":domain"))
                implementation(libs.coroutines.core)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
                implementation(libs.serialization.json)
            }
        }
        androidMain {
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.documentfile)
                implementation(libs.androidx.work.runtime.ktx)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.sentry.kotlin.multiplatform)
            }
        }
        desktopMain {
            dependencies {
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.jna)
                implementation(libs.jna.platform)
                implementation(libs.sentry.kotlin.multiplatform)
            }
        }
        wasmJsMain {
            dependencies {
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

tasks.configureEach {
    val compileUsesCommonMainSources =
        name.startsWith("compile") &&
            (name.contains("Kotlin") || name.startsWith("compileAndroid"))
    if (compileUsesCommonMainSources) {
        dependsOn(generatePhoebeBuildInfo)
    }
}

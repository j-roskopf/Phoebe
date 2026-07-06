import java.util.Properties

plugins {
    id("phoebe.data")
}

fun localProperty(name: String): String? {
    val file = rootProject.file("local.properties")
    if (!file.isFile) return null
    val properties = Properties()
    file.inputStream().use(properties::load)
    return properties.getProperty(name)?.takeIf { it.isNotBlank() }
}

fun configProperty(
    propertyName: String,
    envName: String,
    legacyPropertyName: String,
    legacyEnvName: String,
) =
    providers.provider {
        providers.gradleProperty(propertyName).orNull
            ?: providers.environmentVariable(envName).orNull
            ?: localProperty(propertyName)
            ?: providers.gradleProperty(legacyPropertyName).orNull
            ?: providers.environmentVariable(legacyEnvName).orNull
            ?: localProperty(legacyPropertyName)
            ?: ""
    }
        .map { it.trim().trimEnd('/') }

val phoebeBackendUrl = configProperty(
    propertyName = "phoebe.backend.url",
    envName = "PHOEBE_BACKEND_URL",
    legacyPropertyName = "phoebe.events.backendUrl",
    legacyEnvName = "PHOEBE_EVENTS_BACKEND_URL",
)
val backendConfigOutput = layout.buildDirectory.dir("generated/phoebeBackendConfig/kotlin")

val generatePhoebeBackendBuildConfig = tasks.register("generatePhoebeBackendBuildConfig") {
    val outputDir = backendConfigOutput
    inputs.property("phoebeBackendUrl", phoebeBackendUrl)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("com/phoebe/app/data/PhoebeBackendBuildConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package com.phoebe.app.data

            internal object PhoebeBackendBuildConfig {
                const val productionBackendUrl: String = "${phoebeBackendUrl.get().escapeKotlin()}"
            }
            """.trimIndent() + "\n",
        )
    }
}

kotlin {
    sourceSets {
        commonMain {
            kotlin.srcDir(backendConfigOutput)
            dependencies {
                implementation(project(":core:platform"))
                implementation(project(":data:network"))
                implementation(project(":data:settings"))
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
            }
        }
        commonTest {
            kotlin.srcDir("$rootDir/test-support/network/kotlin")
            dependencies {
                implementation(libs.coroutines.test)
                implementation(libs.ktor.client.mock)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
            }
        }
    }
}

tasks.configureEach {
    val compileUsesCommonMainSources =
        name.startsWith("compile") &&
            (name.contains("Kotlin") || name.startsWith("compileAndroid"))
    if (compileUsesCommonMainSources) {
        dependsOn(generatePhoebeBackendBuildConfig)
    }
}

private fun String.escapeKotlin(): String =
    buildString(length) {
        this@escapeKotlin.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }

package phoebe

import java.io.File
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun Project.libraryNamespace(): String {
    val suffix = path
        .removePrefix(":")
        .split(":")
        .joinToString(".") { segment ->
            segment
                .replace(Regex("[^A-Za-z0-9_]"), "_")
                .lowercase()
        }
    return "com.phoebe.$suffix"
}

internal fun Project.hasIosNativeSourceSets(): Boolean =
    file("src/iosMain").isDirectory ||
        file("src").listFiles()
            ?.any { child -> child.isDirectory && File(child, "iosMain").isDirectory }
        ?: false

internal fun Project.phoebeIosTargetsEnabled(): Boolean {
    val configured = extensions.extraProperties.let { extra ->
        if (!extra.has("phoebeIosTargets")) return@let null
        extra.get("phoebeIosTargets")
    }
    return when (configured) {
        is Boolean -> configured
        is String -> configured.toBoolean()
        null -> shouldEnableIosTargetsByDefault()
        else -> shouldEnableIosTargetsByDefault()
    }
}

internal fun Project.shouldEnableIosTargetsByDefault(): Boolean {
    if (path == ":ui:preview") {
        return false
    }
    if (hasIosNativeSourceSets()) {
        return true
    }
    // Shared libraries without iosMain still publish iosArm64 artifacts because
    // composeApp depends on them from commonMain and Kotlin/Native needs matching
    // targets to link their commonMain code into the iOS app.
    return isPhoebeSharedLibraryModule()
}

internal fun Project.isPhoebeSharedLibraryModule(): Boolean =
    path.startsWith(":") && path != ":" && path != ":composeApp" && path != ":androidApp" && path != ":ui:preview"

internal fun Project.configurePhoebeKmp(
    extension: KotlinMultiplatformExtension,
    enableIosTargets: Boolean = phoebeIosTargetsEnabled(),
) {
    extensions.configure(BasePluginExtension::class.java) {
        archivesName.set(path.removePrefix(":").replace(":", "-"))
    }

    extension.apply {
        jvmToolchain(22)
        compilerOptions {
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }

        jvm("desktop") {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_22)
            }
        }

        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
        wasmJs {
            browser()
        }

        if (enableIosTargets) {
            iosArm64()
            iosSimulatorArm64()
        }

        sourceSets.apply {
            all {
                languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
                languageSettings.optIn("kotlinx.coroutines.FlowPreview")
            }
            named("commonTest") {
                dependencies {
                    implementation(kotlin("test"))
                }
            }
        }
    }
}

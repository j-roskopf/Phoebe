import phoebe.configurePhoebeKmp
import phoebe.libraryNamespace
import phoebe.libs
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.kotlin.multiplatform.library")
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    configurePhoebeKmp(this)

    android {
        namespace = libraryNamespace()
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
    }

    sourceSets {
        named("commonMain") {
            dependencies {
                implementation(libs.findLibrary("compose-runtime").get())
                implementation(libs.findLibrary("compose-foundation").get())
                implementation(libs.findLibrary("compose-material3").get())
                implementation(libs.findLibrary("compose-components-resources").get())
                implementation(libs.findLibrary("compose-ui-tooling-preview").get())
            }
        }
        findByName("androidHostTest")?.apply {
            kotlin.srcDir("src/androidUnitTest/kotlin")
        }
    }
}

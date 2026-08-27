import phoebe.configurePhoebeKmp
import phoebe.libraryNamespace
import phoebe.libs
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.kotlin.multiplatform.library")
    kotlin("multiplatform")
}

kotlin {
    configurePhoebeKmp(this)

    android {
        namespace = libraryNamespace()
        compileSdk = 36
        minSdk = 26
        androidResources {
            enable = false
        }
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        withHostTest {
            isIncludeAndroidResources = false
            isReturnDefaultValues = true
        }
    }

    sourceSets {
        named("commonMain") {
            dependencies {
                implementation(libs.findLibrary("coroutines-core").get())
            }
        }
        findByName("androidHostTest")?.apply {
            kotlin.srcDir("src/androidUnitTest/kotlin")
        }
    }
}

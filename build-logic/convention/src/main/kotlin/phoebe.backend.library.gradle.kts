import phoebe.libs
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(22)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_22)
    }
}

dependencies {
    implementation(libs.findLibrary("coroutines-core").get())
    implementation(libs.findLibrary("serialization-json").get())
    implementation(libs.findLibrary("ktor-server-core").get())
    implementation(libs.findLibrary("ktor-server-content-negotiation").get())
    implementation(libs.findLibrary("ktor-serialization-json").get())

    testImplementation(kotlin("test"))
    testImplementation(libs.findLibrary("coroutines-test").get())
}

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    }
}

rootProject.name = "PhoebeBackend"
includeBackendModules()
include(":domain")

fun includeBackendModules() {
    rootDir.resolve("backend")
        .listFiles()
        .orEmpty()
        .filter { moduleDir -> moduleDir.isDirectory && moduleDir.resolve("build.gradle.kts").isFile }
        .sortedBy { moduleDir -> moduleDir.name }
        .forEach { moduleDir -> include(":backend:${moduleDir.name}") }
}

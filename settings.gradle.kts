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

rootProject.name = "Phoebe"
includeBackendModules()
include(":androidApp")
include(":composeApp")
include(":domain")
include(":navigation")
include(":core:di")
include(":core:platform")
include(":data:database")
include(":data:network")
include(":data:providers:plex")
include(":data:providers:jellyfin")
include(":data:providers:subsonic")
include(":data:providers:musicassistant")
include(":data:catalog")
include(":data:events")
include(":data:session")
include(":data:play-history")
include(":data:settings")
include(":data:lyrics")
include(":data:listenbrainz")
include(":data:local-media")
include(":data:updates")
include(":data:playlists")
include(":data:artwork")
include(":playback")
include(":ui:core")
include(":ui:media")
include(":ui:preview")
include(":feature:auth")
include(":feature:home")
include(":feature:library")
include(":feature:search")
include(":feature:collections")
include(":feature:details")
include(":feature:playback")
include(":feature:radio")
include(":feature:lyrics")
include(":feature:history")
include(":feature:favorites")
include(":feature:settings")

fun includeBackendModules() {
    rootDir.resolve("backend")
        .listFiles()
        .orEmpty()
        .filter { moduleDir -> moduleDir.isDirectory && moduleDir.resolve("build.gradle.kts").isFile }
        .sortedBy { moduleDir -> moduleDir.name }
        .forEach { moduleDir -> include(":backend:${moduleDir.name}") }
}

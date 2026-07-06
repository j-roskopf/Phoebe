plugins {
    id("phoebe.kmp.library")
    id("phoebe.metro")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":core:platform"))
                implementation(project(":domain"))
                implementation(project(":data:catalog"))
                implementation(project(":data:database"))
                implementation(project(":data:events"))
                implementation(project(":data:listenbrainz"))
                implementation(project(":data:musicbrainz"))
                implementation(project(":data:network"))
                implementation(project(":data:play-history"))
                implementation(project(":data:providers:jellyfin"))
                implementation(project(":data:providers:musicassistant"))
                implementation(project(":data:providers:plex"))
                implementation(project(":data:providers:subsonic"))
                implementation(project(":data:session"))
                implementation(project(":data:lyrics"))
                implementation(project(":data:settings"))
                implementation(project(":data:updates"))
                implementation(project(":feature:history"))
                implementation(project(":feature:search"))
                implementation(project(":navigation"))
                implementation(project(":playback"))
                implementation(libs.ktor.client.core)
                implementation(libs.lifecycle.viewmodel)
            }
        }
    }
}

plugins {
    id("phoebe.ui")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":core:platform"))
                implementation(project(":data:artwork"))
                implementation(project(":data:play-history"))
                implementation(project(":data:playlists"))
                implementation(project(":data:providers:jellyfin"))
                implementation(project(":domain"))
                implementation(project(":ui:core"))
                implementation(libs.coil.compose)
                implementation(libs.coil.network.ktor3)
                implementation(libs.ktor.client.core)
            }
        }
        androidMain {
            dependencies {
                implementation(libs.ktor.client.okhttp)
            }
        }
        desktopMain {
            dependencies {
                implementation(libs.ktor.client.java)
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
        commonTest {
            dependencies {
                implementation(libs.coroutines.test)
            }
        }
        desktopTest {
            dependencies {
                implementation(libs.compose.ui.test.junit4)
            }
        }
    }
}

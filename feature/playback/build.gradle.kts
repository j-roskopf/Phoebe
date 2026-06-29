plugins {
    id("phoebe.feature")
}

kotlin {
    sourceSets {
        val filamentMain by creating {
            dependsOn(getByName("commonMain"))
            dependencies {
                implementation(libs.filament.compose)
            }
        }

        commonMain {
                dependencies {
                    implementation(project(":core:platform"))
                    implementation(project(":data:listenbrainz"))
                    implementation(project(":playback"))
                    implementation(project(":ui:media"))
                }
            }
        desktopMain {
            dependsOn(filamentMain)
            dependencies {
                implementation(libs.jnativehook)
            }
        }
        androidMain {
            dependsOn(filamentMain)
        }
        iosMain {
            dependsOn(filamentMain)
        }
        getByName("iosArm64Main").dependsOn(getByName("iosMain"))
        getByName("iosSimulatorArm64Main").dependsOn(getByName("iosMain"))
    }
}

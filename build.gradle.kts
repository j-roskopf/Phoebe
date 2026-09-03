plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.metro) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.sentryJvm) apply false
}

// Coil 3.5.0 follows the latest stable Compose release and publishes its
// Skiko dependency as 0.144.6. Compose 1.12.0 uses 0.150.1, so rewrite only
// Coil's metadata to the version already selected by the Compose dependency graph.
allprojects {
    dependencies {
        components {
            all {
                if (id.group == "io.coil-kt.coil3") {
                    allVariants {
                        withDependencies {
                            val replacedSkikoDependency = removeAll {
                                it.group == "org.jetbrains.skiko" && it.name == "skiko"
                            }
                            if (replacedSkikoDependency) {
                                add("org.jetbrains.skiko:skiko:${libs.versions.skiko.get()}")
                            }
                        }
                    }
                }
            }
        }
    }
}

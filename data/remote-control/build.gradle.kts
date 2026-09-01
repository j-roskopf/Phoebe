plugins {
    id("phoebe.data")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":core:platform"))
                implementation(libs.ktor.network)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.coroutines.test)
            }
        }
    }
}

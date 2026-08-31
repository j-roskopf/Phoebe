plugins {
    id("phoebe.data")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":data:network"))
                implementation(libs.ktor.client.core)
                implementation(libs.coroutines.core)
            }
        }
    }
}

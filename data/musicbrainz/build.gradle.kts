plugins {
    id("phoebe.data")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":core:platform"))
                implementation(project(":data:events"))
                implementation(project(":data:network"))
                implementation(project(":data:settings"))
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
            }
        }
        commonTest {
            kotlin.srcDir("$rootDir/test-support/network/kotlin")
            dependencies {
                implementation(libs.coroutines.test)
                implementation(libs.ktor.client.mock)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
            }
        }
    }
}

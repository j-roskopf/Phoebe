plugins {
    id("phoebe.backend.library")
}

dependencies {
    implementation(project(":backend:core"))
    implementation(project(":domain"))
    implementation(libs.ktor.client.core)
}

import org.gradle.api.tasks.JavaExec

plugins {
    id("phoebe.backend")
}

application {
    mainClass.set("com.phoebe.app.backend.MainKt")
    applicationName = "phoebe-backend"
}

tasks.named<JavaExec>("run") {
    backendSecret("TICKETMASTER_API_KEY", "phoebe.backend.ticketmasterApiKey")?.let {
        environment("TICKETMASTER_API_KEY", it)
    }
    backendSecret("SEATGEEK_CLIENT_ID", "phoebe.backend.seatGeekClientId")?.let {
        environment("SEATGEEK_CLIENT_ID", it)
    }
    backendSecret("GENIUS_ACCESS_TOKEN", "phoebe.backend.geniusAccessToken")?.let {
        environment("GENIUS_ACCESS_TOKEN", it)
    }
}

dependencies {
    implementation(project(":backend:core"))
    implementation(libs.ktor.client.core)
    backendFeatureProjectPaths().forEach { path ->
        implementation(project(path))
    }

    testImplementation(project(":domain"))
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.client.mock)
}

fun backendSecret(envName: String, propertyName: String): String? =
    providers.gradleProperty(envName)
        .orElse(providers.gradleProperty(propertyName))
        .orElse(providers.environmentVariable(envName))
        .orNull
        ?.trim()
        ?.takeIf { it.isNotBlank() }

fun backendFeatureProjectPaths(): List<String> =
    rootProject.subprojects
        .map { it.path }
        .filter { path ->
            path.startsWith(":backend:") &&
                path != project.path &&
                path != ":backend:core"
        }
        .sorted()

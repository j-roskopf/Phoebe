import java.util.Properties

val phoebeVersionName = providers.gradleProperty("phoebe.versionName")
    .orElse(providers.environmentVariable("PHOEBE_VERSION_NAME"))
    .orElse("1.0.0")

val phoebeVersionCode = providers.gradleProperty("phoebe.versionCode")
    .orElse(providers.environmentVariable("PHOEBE_VERSION_CODE"))
    .map(String::toInt)
    .orElse(1)

fun localProperty(name: String): String? {
    val file = rootProject.file("local.properties")
    if (!file.isFile) return null
    val properties = Properties()
    file.inputStream().use(properties::load)
    return properties.getProperty(name)?.takeIf { it.isNotBlank() }
}

fun providerValue(name: String, envName: String): String? =
    providers.gradleProperty(name).orElse(providers.environmentVariable(envName)).orNull

fun secretProperty(name: String, envName: String) =
    providers.gradleProperty(name)
        .orElse(providers.environmentVariable(envName))
        .orElse(providers.provider { localProperty(name).orEmpty() })
        .map { it.trim() }

val googleMapsApiKey = secretProperty("phoebe.googleMaps.apiKey", "PHOEBE_GOOGLE_MAPS_API_KEY")
val googleMapsAndroidApiKey = secretProperty("phoebe.googleMaps.androidApiKey", "PHOEBE_GOOGLE_MAPS_ANDROID_API_KEY")

plugins {
    alias(libs.plugins.androidApplication)
}

android {
    namespace = "com.phoebe.androidapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.phoebe.app"
        minSdk = 26
        targetSdk = 36
        versionCode = phoebeVersionCode.get()
        versionName = phoebeVersionName.get()
        manifestPlaceholders["phoebeGoogleMapsAndroidApiKey"] = googleMapsAndroidApiKey.get()
            .ifBlank { googleMapsApiKey.get() }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    val releaseStoreFile = providerValue("phoebe.android.signing.storeFile", "PHOEBE_ANDROID_SIGNING_STORE_FILE")
    val releaseStorePassword = providerValue("phoebe.android.signing.storePassword", "PHOEBE_ANDROID_SIGNING_STORE_PASSWORD")
    val releaseKeyAlias = providerValue("phoebe.android.signing.keyAlias", "PHOEBE_ANDROID_SIGNING_KEY_ALIAS")
    val releaseKeyPassword = providerValue("phoebe.android.signing.keyPassword", "PHOEBE_ANDROID_SIGNING_KEY_PASSWORD")

    val hasReleaseSigning =
        releaseStoreFile != null &&
            releaseStorePassword != null &&
            releaseKeyAlias != null &&
            releaseKeyPassword != null

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
}

dependencies {
    implementation(project(":composeApp"))
}

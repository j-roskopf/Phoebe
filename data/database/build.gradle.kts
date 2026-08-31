plugins {
    id("phoebe.data")
    id("phoebe.sqldelight")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":core:platform"))
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.async.extensions)
                implementation(libs.sqldelight.coroutines.extensions)
                implementation(libs.sqldelight.primitive.adapters)
            }
        }
        androidMain {
            dependencies {
                implementation(libs.sqldelight.android.driver)
            }
        }
        desktopMain {
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
        desktopTest {
            kotlin.srcDir("$rootDir/test-support/database/desktop/kotlin")
            dependencies {
                implementation(libs.junit)
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
        wasmJsMain {
            dependencies {
                implementation(libs.kotlinx.browser)
                implementation(libs.sqldelight.web.worker.driver)
                implementation(npm("@cashapp/sqldelight-sqljs-worker", libs.versions.sqldelight.get()))
                implementation(npm("sql.js", "1.8.0"))
            }
        }
        iosMain {
            dependencies {
                implementation(libs.sqldelight.native.driver)
            }
        }
    }
}

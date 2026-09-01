plugins {
    id("phoebe.feature")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":core:platform"))
                implementation(project(":data:remote-control"))
                implementation(project(":data:updates"))
            }
        }
    }
}

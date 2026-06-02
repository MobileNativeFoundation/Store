plugins {
    id("org.mobilenativefoundation.store.multiplatform")
}

kotlin {

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
            }
        }
    }
}

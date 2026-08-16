plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.store6Core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(projects.store6Testing)
                // Test-only: MutationStore is exercised as a Store implementation; the production
                // adapter has no dependency on the mutations module.
                implementation(projects.store6Mutations)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
            }
        }
    }
}

android {
    namespace = "org.mobilenativefoundation.store6.realtime"
}

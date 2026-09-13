plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(projects.testing)
                // Test-only: MutationStore is exercised as a Store implementation; the production
                // adapter has no dependency on the mutations module.
                implementation(projects.mutations)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
            }
        }
    }
}

android {
    namespace = "org.mobilenativefoundation.store6.realtime"
}

plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform")
}

kotlin {
    js {
        nodejs {
            testTask {
                useMocha {
                    // Keep the runner above the 240s StoreInvalidationStressTest watchdog so
                    // runTest always owns cancellation and cleanup.
                    timeout = "300s"
                }
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.kotlinx.coroutines.core)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(projects.store6Testing)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
            }
        }
    }
}

android {
    namespace = "org.mobilenativefoundation.store6.core"
}

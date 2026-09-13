plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.core)
                api(libs.ktor.client.core)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(projects.testing)
                implementation(libs.ktor.client.mock)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
            }
        }

        // The real-engine lane. MockEngine short-circuits the transport, so cancellation,
        // timeouts, redirects, and retry composition can only be observed against a real client
        // and a real server. JVM-only by choice: one hosted lane is enough to pin the behavior,
        // and an in-process server is not available on every target.
        val jvmTest by getting {
            dependencies {
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
            }
        }
    }
}

android {
    namespace = "org.mobilenativefoundation.store6.ktor"
}

plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                // Kit and fake signatures expose core/seam types.
                api(projects.store6Core)
                // The kits' plainly inherited @Test members must resolve in consumer test
                // compilations on every target; kotlin-test is part of this artifact's API.
                api(kotlin("test"))
                // The kits' inherited members are expression-bodied `: TestResult = runTest {…}`,
                // so kotlinx.coroutines.test.TestResult is in the published ABI on all 12 targets
                // (plain inheritance — no expect/actual, no forwarding members on js/wasmJs).
                // This is the exact evidence phase0 item 35 conditioned api scope on (approved).
                api(libs.kotlinx.coroutines.test)
                // The SoT kit BODIES use turbine (app.cash.turbine.test/turbineScope), but turbine
                // never appears in a source signature. The declaration is deliberately narrower
                // than the landed contract-tests api(libs.turbine). Effective publication scope is
                // target-dependent: JVM/Android/JS/Wasm POMs publish it at runtime, while Gradle
                // metadata and Native variants place it on API/compile variants. That live tooling
                // shape is recorded in the decision doc and remains flagged for Matt.
                implementation(libs.turbine)
            }
        }
        val jvmMain by getting {
            dependencies {
                // Mirrors the landed contract-tests module: JUnit-variant resolution for JVM consumers.
                api(kotlin("test-junit"))
            }
        }
        val androidMain by getting {
            dependencies {
                api(kotlin("test-junit"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.turbine)
            }
        }
    }
}

android {
    namespace = "org.mobilenativefoundation.store6.testing"
}

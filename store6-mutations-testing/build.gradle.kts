plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.store6Mutations)
                api(kotlin("test"))
                api(libs.kotlinx.coroutines.test)
            }
        }
        val jvmMain by getting {
            dependencies {
                api(kotlin("test-junit"))
            }
        }
        val androidMain by getting {
            dependencies {
                api(kotlin("test-junit"))
            }
        }
    }
}

android {
    namespace = "org.mobilenativefoundation.store6.mutations.testing"
}

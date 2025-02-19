plugins {
    id("org.mobilenativefoundation.store.multiplatform")
}

kotlin {

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.kotlinx.atomic.fu)
                api(projects.core)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

android {
    namespace = "org.mobilenativefoundation.store.cache"
}

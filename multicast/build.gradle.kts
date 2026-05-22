plugins {
    id("org.mobilenativefoundation.store.multiplatform")
}

kotlin {

    sourceSets {

        commonMain {
            dependencies {
                api(libs.kotlinx.atomic.fu)
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
            }
        }
    }
}

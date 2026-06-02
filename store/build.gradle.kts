plugins {
    id("org.mobilenativefoundation.store.multiplatform")
    alias(libs.plugins.kover)
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.core)
                api(libs.kotlinx.atomic.fu)
                implementation(libs.touchlab.kermit)
                implementation(projects.multicast)
                implementation(projects.cache)
                api(projects.core)
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

kotlin {
    android {
        namespace = "org.mobilenativefoundation.store.store5"
    }
}

kover {

    reports {
        total {
            xml {
                onCheck = true
                xmlFile.set(file("${layout.buildDirectory}/reports/kover/coverage.xml"))
            }
        }
    }
}

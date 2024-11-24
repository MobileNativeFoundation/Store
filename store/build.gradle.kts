plugins {
    id("org.mobilenativefoundation.store.multiplatform")
    alias(libs.plugins.kover)
    id("dev.mokkery") version "2.5.1"
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.kotlinx.datetime)
                api(libs.kotlinx.atomic.fu)
                implementation(libs.touchlab.kermit)
                implementation(projects.multicast)
                implementation(projects.cache)
                api(projects.core)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
            }
        }
    }
}

android {
    namespace = "org.mobilenativefoundation.store.store5"
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

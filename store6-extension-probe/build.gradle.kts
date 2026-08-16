import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
}

kotlin {
    explicitApi()
    applyDefaultHierarchyTemplate()

    androidTarget()
    jvm()

    iosX64()
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    watchosArm64()
    tvosArm64()

    linuxX64()
    mingwX64()

    js {
        nodejs()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
    }

    jvmToolchain(11)

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.store6Core)
                api(kotlin("test"))
                api(libs.kotlinx.coroutines.test)
                api(libs.turbine)
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
    namespace = "org.mobilenativefoundation.store6.extensionprobe"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

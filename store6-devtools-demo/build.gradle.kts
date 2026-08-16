import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.application")
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
}

kotlin {
    jvmToolchain(11)
    androidTarget()
    jvm("desktop")
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target: KotlinNativeTarget ->
        target.binaries.framework {
            baseName = "DevtoolsDemo"
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.store6Core)
                implementation(projects.store6Testing)
                implementation(projects.store6Compose)
                implementation(projects.store6Devtools)
                implementation(projects.store6DevtoolsInspector)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
            }
        }
        val androidMain by getting {
            dependencies {
                // Direct coordinate by design: unpublished demo, catalog untouched.
                implementation("androidx.activity:activity-compose:1.10.1")
            }
        }
        val desktopMain by getting {
            dependencies { implementation(compose.desktop.currentOs) }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "org.mobilenativefoundation.store6.devtoolsdemo.MainKt"
    }
}

android {
    namespace = "org.mobilenativefoundation.store6.devtoolsdemo"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.mobilenativefoundation.store6.devtoolsdemo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

@file:Suppress("UnstableApiUsage")

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("app.cash.zipline")
}

group = "com.dropbox.market.samples.notes"

kotlin {
    android()
    js {
        browser()
        binaries.executable()
    }
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(Deps.Kotlinx.serializationCore)
                implementation(Deps.Kotlinx.serializationJson)
            }
        }

        val jsMain by getting

        val jvmMain by getting {
            dependencies {
                with(Deps.Cash) {
                    implementation(okhttp)
                }
            }
        }

        val androidMain by getting {
            dependencies {
                dependsOn(jvmMain)
                with(Deps.Kotlinx) {
                    implementation(coroutinesAndroid)
                    implementation(serializationCore)
                }
                with(Deps.AndroidX) {
                    implementation(coreKtx)
                    implementation(appCompat)
                }
            }
        }
    }
}

android {
    compileSdkVersion(Version.androidCompileSdk)
    compileSdk = Version.androidCompileSdk

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = Version.composeCompiler
    }

}

rootProject.plugins.withType(org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin::class.java) {
    rootProject.the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension>().versions.webpackCli.version =
        "4.9.0"
}
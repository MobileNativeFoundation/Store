
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.dokka")
    id("org.jetbrains.kotlinx.kover")
    `maven-publish`
    id("kotlinx-atomicfu")
    id("org.jetbrains.compose") version("1.5.1")
}

kotlin {
    androidTarget()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(project(":store"))
                implementation(project(":cache"))
                implementation(compose.runtime)
                implementation(libs.molecule.runtime)
                implementation(compose.ui)
                implementation(compose.foundation)
                implementation(compose.material)
                api(project(":core"))
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.paging.runtime)
                implementation(libs.androidx.paging.compose)
            }
        }
        @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.turbine)
                implementation(libs.kotlinx.coroutines.test)
                implementation(compose.uiTestJUnit4)
                implementation(compose.ui)
            }
        }
    }
}

android {
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    compileSdk = 33

    defaultConfig {
        minSdk = 24
    }

    lint {
        disable += "ComposableModifierFactory"
        disable += "ModifierFactoryExtensionFunction"
        disable += "ModifierFactoryReturnType"
        disable += "ModifierFactoryUnreferencedReceiver"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

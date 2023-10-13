
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
    android()
    jvm()

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


            }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.paging.runtime)
            }
        }
    }
}

android {

    compileSdk = 33

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
}

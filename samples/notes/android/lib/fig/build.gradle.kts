@file:Suppress("UnstableApiUsage")

plugins {
    kotlin("plugin.serialization")
    id("com.android.library")
    kotlin("android")
}

group = "org.mobilenativefoundation.store.notes.android"

android {
    compileSdk = Version.androidCompileSdk

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = Version.composeCompiler
    }

    defaultConfig {
        minSdk = 24
        targetSdk = 31
    }
}

dependencies {
    implementation(project(":store"))

    with(Deps.Compose) {
        implementation(material)
        implementation(ui)
    }
}

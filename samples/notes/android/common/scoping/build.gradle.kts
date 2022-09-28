@file:Suppress("UnstableApiUsage")

plugins {
    id("com.android.library")
    kotlin("android")
    id("kotlin-kapt")
    id("com.squareup.anvil")
}

group = "com.dropbox.notes.android"


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
}

dependencies {
    implementation(project(":store"))

    with(Deps.Androidx) {
        implementation(activityCompose)
    }

    with(Deps.Compose) {
        implementation(material)
        implementation(ui)
    }

    with(Deps.Dagger) {
        implementation(dagger)
        kapt(daggerCompiler)
    }
}
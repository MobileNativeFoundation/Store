@file:Suppress("UnstableApiUsage")

plugins {
    id("com.android.application")
    kotlin("android")
    id("kotlin-kapt")
    id("com.squareup.anvil")
}

group = "com.dropbox.notes.android"

android {
    compileSdk = Version.androidCompileSdk

    defaultConfig {
        applicationId = "com.dropbox.notes.android"
        minSdkVersion(Version.androidMinSdk)
        targetSdkVersion(Version.androidTargetSdk)
    }

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

    packagingOptions {
        resources {
            excludes += "META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":store"))
    implementation(project(":samples:notes:android:common:scoping"))

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

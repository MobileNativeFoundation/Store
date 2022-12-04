@file:Suppress("UnstableApiUsage")

plugins {
    kotlin("plugin.serialization")
    id("com.android.library")
    kotlin("android")
    id("kotlin-kapt")
    id("com.squareup.anvil")
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

    with(Deps.Kotlinx) {
        implementation(serializationCore)
        implementation(serializationJson)
    }

    with(Deps.Androidx) {
        implementation(appCompat)
        implementation(lifecycleViewmodelKtx)
        implementation(lifecycleRuntimeKtx)
        implementation(activityCompose)
        implementation(coreKtx)
    }

    with(Deps.Ktor) {
        implementation(clientCore)
        implementation(clientCio)
    }

    with(Deps.Dagger) {
        implementation(dagger)
        kapt(daggerCompiler)
    }
}

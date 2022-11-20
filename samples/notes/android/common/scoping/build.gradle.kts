@file:Suppress("UnstableApiUsage")

plugins {
    kotlin("plugin.serialization")
    id("com.android.library")
    kotlin("android")
    id("kotlin-kapt")
    id("app.cash.molecule")
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
    implementation(project(":samples:notes:android:common:api"))

    with(Deps.Androidx) {
        implementation(activityCompose)
    }

    with(Deps.Compose) {
        implementation(material)
        implementation(ui)
    }

    implementation(Deps.Kotlinx.serializationCore)
    implementation(Deps.Kotlinx.serializationJson)

    with(Deps.Androidx) {
        implementation(appCompat)
        implementation(lifecycleViewmodelKtx)
        implementation(lifecycleRuntimeKtx)
        implementation(activityCompose)
        implementation(coreKtx)
        implementation(activityCompose)
    }

    val ktor_version = "2.1.1"
    implementation("io.ktor:ktor-client-core:$ktor_version")
    implementation("io.ktor:ktor-client-cio:$ktor_version")

    with(Deps.Dagger) {
        implementation(dagger)
        kapt(daggerCompiler)
    }
}
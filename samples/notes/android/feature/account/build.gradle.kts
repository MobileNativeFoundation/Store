@file:Suppress("UnstableApiUsage")

plugins {
    kotlin("plugin.serialization")
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
    implementation(project(":samples:notes:android:common:scoping"))
    implementation(project(":samples:notes:android:common:api"))

    with(Deps.Compose) {
        implementation(material)
        implementation(ui)
    }

    implementation(Deps.Kotlinx.serializationCore)
    implementation(Deps.Kotlinx.serializationJson)

    with(Deps.Coil) {
        implementation(compose)
    }

    with(Deps.Androidx) {
        implementation(appCompat)
        implementation(lifecycleViewmodelKtx)
        implementation(lifecycleRuntimeKtx)
        implementation(activityCompose)
        implementation(coreKtx)
    }

    val dagger_version = "2.44"
    implementation("com.google.dagger:dagger:$dagger_version")
    kapt("com.google.dagger:dagger-compiler:$dagger_version")

    val ktor_version = "2.1.1"
    implementation("io.ktor:ktor-client-core:$ktor_version")
    implementation("io.ktor:ktor-client-cio:$ktor_version")
}

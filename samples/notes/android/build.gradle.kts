@file:Suppress("UnstableApiUsage")

plugins {
    kotlin("android")
    kotlin("kapt")
    kotlin("plugin.serialization")
    id("com.android.application")
    id("dagger.hilt.android.plugin")
    id("app.cash.zipline")
    id("com.squareup.sqldelight")
}

group = "com.dropbox.market.notes.android"

android {
    compileSdk = Version.androidCompileSdk

    defaultConfig {
        applicationId = "com.dropbox.market.samples.notes"
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
    implementation(project(":samples:notes:android:fig"))
    implementation(project(":samples:notes:common"))

    implementation("com.google.dagger:hilt-android:2.42")
    kapt("com.google.dagger:hilt-android-compiler:2.42")
    annotationProcessor("androidx.hilt:hilt-compiler:1.0.0")
    implementation("androidx.hilt:hilt-navigation-compose:1.0.0")

    with(Deps.AndroidX) {
        implementation(appCompat)
    }

    with(Deps.Compose) {
        implementation(material)
        implementation(navigation)
        implementation(ui)
    }

    with(Deps.Mavericks) {
        implementation(mavericksCompose)
    }

    implementation(Deps.Kotlinx.serializationCore)
    implementation(Deps.Kotlinx.serializationJson)

    with(Deps.Cash.Zipline) {
        implementation(zipline)
    }

    with(Deps.Cash.SqlDelight) {
        implementation(runtime)
        implementation(coroutineExtensions)
        implementation(driverAndroid)
    }

    with(Deps.Ktor){
        implementation(clientAndroid)
        implementation(clientJson)
        implementation(clientSerialization)
        implementation(kotlinxJson)
        implementation(contentNegotiation)
    }
}

kapt {
    correctErrorTypes = true
}

sqldelight {
    database("NotesDatabase") {
        packageName = "com.dropbox.market.notes.android"
    }
}
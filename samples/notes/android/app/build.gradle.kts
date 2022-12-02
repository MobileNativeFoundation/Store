@file:Suppress("UnstableApiUsage")

plugins {
    kotlin("plugin.serialization")
    id("com.android.application")
    kotlin("android")
    id("kotlin-kapt")
    id("com.squareup.anvil")
    id("com.squareup.sqldelight")
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
    implementation(project(":samples:notes:android:feature:account"))
    implementation(project(":samples:notes:android:feature:home"))
    implementation(project(":samples:notes:android:feature:explore"))
    implementation(project(":samples:notes:android:lib:navigation"))
    implementation(project(":samples:notes:android:lib:result"))
    implementation(project(":samples:notes:android:lib:fig"))
    implementation(project(":samples:notes:android:common:scoping"))
    implementation(project(":samples:notes:android:common:api"))

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
        implementation(navigationCompose)
    }

    with(Deps.Dagger) {
        implementation(dagger)
        kapt(daggerCompiler)
    }

    with(Deps.SqlDelight) {
        implementation(runtime)
        implementation(coroutineExtensions)
        implementation(driverAndroid)
    }
}

sqldelight {
    database("NotesDatabase") {
        packageName = "org.mobilenativefoundation.store.notes.android.app"
    }
}

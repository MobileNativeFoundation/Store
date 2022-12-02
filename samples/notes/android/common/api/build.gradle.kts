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

    defaultConfig {
        minSdk = 24
        targetSdk = 31
    }
}

dependencies {
    implementation(project(":samples:notes:android:lib:result"))

    with(Deps.Dagger) {
        implementation(dagger)
        kapt(daggerCompiler)
    }
}

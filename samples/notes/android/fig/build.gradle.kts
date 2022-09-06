@file:Suppress("UnstableApiUsage")

plugins {
kotlin("android")
    id("com.android.library")
}

group = "com.dropbox.market.notes.fig"

android {
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

    compileSdk = 32
}

dependencies {
    with(Deps.Compose) {
        implementation(runtime)
        implementation(material)
        implementation(foundationLayout)
    }
}
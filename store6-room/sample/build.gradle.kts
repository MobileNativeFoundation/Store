plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.ksp)
    application
}

kotlin {
    jvmToolchain(11)
    sourceSets {
        getByName("main")
            .languageSettings
            .optIn("org.mobilenativefoundation.store6.core.ExperimentalStoreApi")
    }
}

dependencies {
    implementation(projects.store6Core)
    implementation(projects.store6Room)
    implementation(libs.room3.runtime)
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.room3.compiler)
}

application { mainClass.set("org.mobilenativefoundation.store6.room.sample.MainKt") }

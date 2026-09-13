plugins {
    id("org.jetbrains.kotlin.jvm")
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
    implementation(projects.core)
    implementation(projects.file)
}

application { mainClass.set("org.mobilenativefoundation.store6.file.sample.MainKt") }

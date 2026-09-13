plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin { jvmToolchain(11) }

dependencies {
    implementation(projects.opentelemetry)
    implementation(libs.opentelemetry.sdk)
    // In-memory reader and exporter keep the sample self-contained and its exports
    // machine-checkable in CI.
    implementation(libs.opentelemetry.sdk.testing)
}

application { mainClass.set("org.mobilenativefoundation.store6.opentelemetry.sample.MainKt") }

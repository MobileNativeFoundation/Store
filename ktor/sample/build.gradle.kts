plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin { jvmToolchain(11) }

dependencies {
    implementation(projects.ktor)
    implementation(libs.ktor.client.mock)
}

application { mainClass.set("org.mobilenativefoundation.store6.ktor.sample.MainKt") }

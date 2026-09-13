plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin { jvmToolchain(11) }

dependencies { implementation(projects.mutations) }

application { mainClass.set("MainKt") }

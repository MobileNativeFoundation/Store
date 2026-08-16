plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin { jvmToolchain(11) }

dependencies { implementation(projects.store6Mutations) }

application { mainClass.set("MainKt") }

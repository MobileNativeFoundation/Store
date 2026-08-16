plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin { jvmToolchain(11) }

dependencies { implementation(projects.core) }

application { mainClass.set("org.mobilenativefoundation.store6.quickstart.MainKt") }

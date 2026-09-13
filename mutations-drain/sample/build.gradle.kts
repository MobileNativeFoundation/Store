plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin { jvmToolchain(11) }

dependencies { implementation(projects.mutationsDrain) }

application { mainClass.set("DrainSchedulerSampleKt") }

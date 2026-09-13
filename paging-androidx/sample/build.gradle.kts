plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin { jvmToolchain(11) }

dependencies {
    implementation(projects.pagingAndroidx)
    implementation(projects.mutations)
}

application { mainClass.set("org.mobilenativefoundation.store6.paging.sample.MainKt") }

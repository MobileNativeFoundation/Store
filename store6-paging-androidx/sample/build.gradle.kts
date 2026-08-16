plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin { jvmToolchain(11) }

dependencies {
    implementation(projects.store6PagingAndroidx)
    implementation(projects.store6Mutations)
}

application { mainClass.set("org.mobilenativefoundation.store6.paging.sample.MainKt") }

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
    alias(libs.plugins.sqldelight)
}

kotlin { jvmToolchain(11) }

dependencies {
    implementation(projects.store6Sqldelight)
    implementation(libs.sqldelight.sqlite.driver)
}

sqldelight {
    databases {
        create("SampleDatabase") {
            packageName.set("org.mobilenativefoundation.store6.sqldelight.sample.db")
        }
    }
}

application { mainClass.set("org.mobilenativefoundation.store6.sqldelight.sample.MainKt") }

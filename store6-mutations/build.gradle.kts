plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.store6Core)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(projects.store6Testing)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
            }
        }
    }
}

android {
    namespace = "org.mobilenativefoundation.store6.mutations"
}

// R1-13's JVM-only API-surface audit reads the committed BCV KLib dump. The lookup is explicit,
// never a working-directory assumption (021 plan T4.8).
tasks.withType<Test>().configureEach {
    if (name == "jvmTest") {
        systemProperty(
            "store6.mutations.apiDumpDir",
            layout.projectDirectory.dir("api").asFile.absolutePath,
        )
    }
}

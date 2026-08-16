plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.core)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(projects.testing)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(libs.lincheck)
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

tasks.named("jvmTest", org.jetbrains.kotlin.gradle.targets.jvm.tasks.KotlinJvmTest::class) {
    // The Lincheck model-checking budget exceeds every default hosted CI lane: measured
    // 2h40m-3h13m on hosted runners vs ~58-59m locally at the current suite, and once
    // 9h23m locally at an earlier revision. The scheduled full-suite workflow passes
    // -Pstore6.fullJvmSuite to run it; nothing else does.
    if (!providers.gradleProperty("store6.fullJvmSuite").isPresent) {
        filter {
            excludeTestsMatching("org.mobilenativefoundation.store6.mutations.MutationJournalLincheckTest")
        }
    }
}

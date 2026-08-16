plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform")
    alias(libs.plugins.kotlin.compose.compiler)
}

val store6StabilityConfig = layout.projectDirectory.file("stability/store6-stability.conf")

composeCompiler {
    // Dogfoods the shipped consumer snippet: core types are stable inside this module's own
    // composables. The conf file lands in this same task (T1) so this wiring never dangles.
    stabilityConfigurationFiles.add(store6StabilityConfig)
}

// The Compose compiler plugin does not register stabilityConfigurationFiles as a task input, so
// a conf-only edit would otherwise leave these compilations UP-TO-DATE against stale settings.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    inputs.file(store6StabilityConfig).withPathSensitivity(PathSensitivity.RELATIVE)
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.core)
                // PIN (preflight): CMP 1.8.2 is the pinned Compose Multiplatform runtime line.
                api(libs.jetbrains.compose.runtime)
                // lifecycle-runtime-compose 2.9.1 publishes every canonical Store6 target,
                // including linuxX64, mingwX64, tvosArm64 and watchosArm64 (verified against the
                // published Gradle module metadata), so the lifecycle-gated entry points live in
                // commonMain and ship on all 12 targets rather than a restricted tier.
                api(libs.jetbrains.lifecycle.runtime.compose)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(projects.testing)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

android {
    namespace = "org.mobilenativefoundation.store6.compose"

    // The shared commonTest suites drive a real Composition on every target, and the Compose
    // runtime traces composition disposal through android.os.Trace. Under Android local unit
    // tests that class is an unimplemented android.jar stub, so it throws instead of no-opping.
    // Returning stub defaults keeps the Android variant running the same suites as every other
    // target; no assertion in this module depends on an android.jar return value.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

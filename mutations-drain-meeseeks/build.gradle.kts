plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform.subset")
    // Versioned alias(libs.plugins.kotlin.serialization) fails: root buildscript already
    // classpaths libs.kotlin.serialization.plugin, so Gradle cannot check compatibility.
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    // Meeseeks 1.1.1 publishes Java 17 bytecode and a public inline API; compiling against it
    // and running its classes require a Java 17 toolchain. Android compilation still targets
    // the repo-wide Java 11 through the Android compileOptions.
    jvmToolchain(17)

    androidTarget()
    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    js {
        nodejs()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.mutationsDrain)
                api(libs.meeseeks.runtime)
                implementation(libs.kotlinx.serialization.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
            }
        }
    }
}

android {
    namespace = "org.mobilenativefoundation.store6.mutations.drain.meeseeks"
}

tasks.named("jvmTest", org.jetbrains.kotlin.gradle.targets.jvm.tasks.KotlinJvmTest::class) {
    // Meeseeks 1.1.1's bundled Quartz store pairs StdJDBCDelegate with the SQLite driver.
    // Trigger operations fail with SQLFeatureNotSupportedException and scheduled work never
    // fires (see mutations-drain-meeseeks/README.md, JVM scheduling). Default CI excludes
    // the two suites that observe that gap; -Pstore6.meeseeksJvmIntegration runs them.
    if (!providers.gradleProperty("store6.meeseeksJvmIntegration").isPresent) {
        filter {
            excludeTestsMatching(
                "org.mobilenativefoundation.store6.mutations.drain.meeseeks.MeeseeksExecutionIntegrationTest",
            )
            excludeTestsMatching(
                "org.mobilenativefoundation.store6.mutations.drain.meeseeks.MeeseeksRecoveryIntegrationTest",
            )
        }
    }
}

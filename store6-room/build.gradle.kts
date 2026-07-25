plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform.subset")
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

kotlin {
    // EXACT Room-3.0.0-supported subset. room3 publishes no iosX64 variant, and no js/wasmJs/
    // mingwX64 artifacts for this module's scope; androidTarget() is mandatory under the subset
    // plugin. js/wasmJs are Room-3-supported and tracked as a separate follow-up issue.
    androidTarget()
    jvm()

    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    watchosArm64()
    tvosArm64()

    linuxX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.store6Core)
                api(libs.room3.runtime)
            }
        }
        val commonTest by getting
        val hostTest by creating {
            dependsOn(commonTest)
            dependencies {
                implementation(projects.store6Testing)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
                implementation(libs.androidx.sqlite.bundled)
            }
        }
        val jvmTest by getting {
            dependsOn(hostTest)
        }
        val nativeTest by getting {
            dependsOn(hostTest)
        }
        listOf(
            "jvmTest",
            "iosArm64Test",
            "iosSimulatorArm64Test",
            "macosArm64Test",
            "watchosArm64Test",
            "tvosArm64Test",
            "linuxX64Test",
        ).forEach { sourceSetName ->
            getByName(sourceSetName)
                .languageSettings
                .optIn("org.mobilenativefoundation.store6.core.ExperimentalStoreApi")
        }
    }
}

dependencies {
    // Room codegen runs only for test compilations: main ships annotations-only entities/DAO;
    // the @Database classes live in test fixtures (and in the user's app at consumption time).
    listOf(
        "kspJvmTest",
        "kspIosArm64Test",
        "kspIosSimulatorArm64Test",
        "kspMacosArm64Test",
        "kspWatchosArm64Test",
        "kspTvosArm64Test",
        "kspLinuxX64Test",
    ).forEach { configuration ->
        add(configuration, libs.room3.compiler)
    }
}

// Room 3's Gradle plugin registers its extension as `room3`, not Room 2's `room`
// (androidx.room3.gradle.RoomGradlePlugin creates it under that name). Every @Database in this
// repo sets exportSchema = false — adapter main code ships entities/DAO only, no @Database — so
// no schema JSON is generated; the directory is declared to satisfy the plugin's contract.
room3 {
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "org.mobilenativefoundation.store6.room"
}

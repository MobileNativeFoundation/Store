import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform")
}

// AC-5: byte-identical borrow of core's SoT-substitution-capable conformance suites (see decision doc).
// Include list re-pinned post-007: 10 files, unchanged by 007 (the wave's only core-test writer).
val borrowCoreConformanceSources by tasks.registering(Sync::class) {
    description = "Copies core's SoT-substitution-capable conformance suites, unchanged, for AC-5 runs."
    from(rootProject.layout.projectDirectory.dir("store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core")) {
        include(
            "StoreConformanceTest.kt", "StoreInvalidationConformanceTest.kt",
            "EmissionSequenceConformanceTest.kt", "SingleFlightConformanceTest.kt",
            "FreshnessPolicyConformanceTest.kt", "StoreRevalidationConformanceTest.kt",
            "StoreInvalidationStressTest.kt", "SourceOfTruthSubstitutionTest.kt",
            "TestKey.kt", "NamespacedTestKey.kt",
        )
    }
    into(layout.buildDirectory.dir("borrowedConformance/org/mobilenativefoundation/store6/core"))
}

val linuxSqliteLibraryDirectory = providers.exec {
    commandLine("pkg-config", "--variable=libdir", "sqlite3")
}.standardOutput.asText.map { output ->
    output.trim().also { directory ->
        require(directory.isNotEmpty()) {
            "pkg-config returned an empty sqlite3 library directory"
        }
    }
}

kotlin {
    // Mirrors SQLDelight's linkSqlite() without applying its codegen/database plugin.
    targets
        .filterIsInstance<KotlinNativeTarget>()
        .flatMap { it.binaries }
        .forEach { binary ->
            if (
                binary is TestExecutable &&
                HostManager.host.family == Family.LINUX &&
                binary.target.konanTarget.family == Family.LINUX
            ) {
                // The Linux test runs on the same host that supplies sqlite3. Kotlin/Native's
                // glibc-2.19 sysroot cannot resolve the host library's newer indirect GLIBC
                // versions at link time; the matching host loader resolves them at execution.
                binary.linkerOpts(
                    "-L${linuxSqliteLibraryDirectory.get()}",
                    "-lsqlite3",
                    "--allow-shlib-undefined",
                )
            } else {
                binary.linkerOpts("-lsqlite3")
            }
        }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.store6Core)
                api(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines.extensions)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(projects.store6Testing)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
            }
        }
        val commonSqlTest by creating {
            dependsOn(commonTest)
            kotlin.srcDir(borrowCoreConformanceSources)
        }
        val jvmTest by getting {
            dependsOn(commonSqlTest)
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
        val androidUnitTest by getting {
            dependsOn(commonSqlTest)
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
        val nativeSqlTest by creating {
            dependsOn(commonSqlTest)
            dependencies {
                implementation(libs.sqldelight.native.driver)
            }
        }
        listOf(
            "iosX64Test",
            "iosArm64Test",
            "iosSimulatorArm64Test",
            "macosArm64Test",
            "linuxX64Test",
            "mingwX64Test",
        ).forEach { getByName(it).dependsOn(nativeSqlTest) }
    }
}

android {
    namespace = "org.mobilenativefoundation.store6.sqldelight"
}

tasks.withType<KotlinNativeLink>().configureEach {
    if (binary is TestExecutable) {
        val targetFamily = binary.target.konanTarget.family
        onlyIf("native SQLDelight test binaries require the current host SDK's sqlite3") {
            val hostFamily = HostManager.host.family
            targetFamily == hostFamily ||
                (hostFamily.isAppleFamily && targetFamily.isAppleFamily)
        }
    }
}

tasks.withType<KotlinNativeTest>().configureEach {
    // KGP 2.1.21 can race native-test stdout/stderr handlers and corrupt Gradle
    // 8.11.1's captured-output index after an executable succeeds. Keep JUnit
    // testcase/failure XML, but do not read the affected output index.
    reports.html.required.set(false)
    reports.junitXml.apply {
        isOutputPerTestCase = false
        includeSystemOutLog.set(false)
        includeSystemErrLog.set(false)
    }

    doLast {
        // allTests aggregates binary test results and would reopen the same index.
        // Preserve results.bin while omitting only captured output from its report.
        listOf("output.bin", "output.bin.idx").forEach { fileName ->
            val outputFile = binaryResultsDirectory.file(fileName).get().asFile
            check(!outputFile.exists() || outputFile.delete()) {
                "Could not remove corrupt Gradle test-output file: $outputFile"
            }
        }
    }
}

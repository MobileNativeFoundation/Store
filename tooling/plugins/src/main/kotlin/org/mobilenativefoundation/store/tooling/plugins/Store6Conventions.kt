@file:Suppress("UnstableApiUsage")

package org.mobilenativefoundation.store.tooling.plugins

import com.android.build.api.dsl.LibraryExtension
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import kotlinx.validation.ApiValidationExtension
import kotlinx.validation.ExperimentalBCVApi
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTargetWithSimulatorTests
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

/**
 * Conventions shared by every store6 library module (full and subset variants):
 * KMP + android-library + vanniktech publishing + BCV(klib) + Dokka, explicitApi,
 * default hierarchy, jvmToolchain(11), test deps, simulator-device wiring, android defaults.
 * Subset modules declare their own targets AFTER this applies; androidTarget() is REQUIRED
 * (android library config + "release"-variant publishing are unconditional here), and
 * target spellings must exactly match Store6MultiplatformConventionPlugin's declarations.
 */
internal fun Project.configureStore6Module() {
    with(pluginManager) {
        apply("org.jetbrains.kotlin.multiplatform")
        apply("com.android.library")
        apply("com.vanniktech.maven.publish")
        apply("org.jetbrains.dokka")
        apply("org.jetbrains.kotlinx.binary-compatibility-validator")
        apply("org.jetbrains.kotlinx.kover")
    }

    // Kover's on-the-fly agent must not attach to the scheduled full mutations JVM
    // suite: Lincheck performs its own bytecode transformation (agent-conflict risk)
    // and that hosted lane already runs ~3h. Only store6-full-jvm.yml passes
    // -Pstore6.fullJvmSuite; every other lane keeps coverage instrumentation.
    if (providers.gradleProperty("store6.fullJvmSuite").isPresent) {
        extensions.configure<KoverProjectExtension> {
            currentProject {
                instrumentation {
                    disabledForTestTasks.add("jvmTest")
                }
            }
        }
    }

    extensions.configure<ApiValidationExtension> {
        @OptIn(ExperimentalBCVApi::class)
        klib {
            enabled = true
        }
    }

    tasks.configureEach {
        if (name.endsWith("ApiCheck")) {
            mustRunAfter("${name.removeSuffix("Check")}Dump")
        }
    }

    extensions.configure<KotlinMultiplatformExtension> {
        explicitApi()
        applyDefaultHierarchyTemplate()
        jvmToolchain(11)

        targets.withType<KotlinJvmTarget>().configureEach {
            compilerOptions {
                jvmDefault.set(JvmDefaultMode.DISABLE)
            }
        }

        targets.withType<KotlinAndroidTarget>().configureEach {
            compilerOptions {
                jvmDefault.set(JvmDefaultMode.DISABLE)
            }
        }

        providers.gradleProperty("store6.iosSimulatorDevice").orNull?.let { device ->
            targets.withType<KotlinNativeTargetWithSimulatorTests>().configureEach {
                testRuns.configureEach {
                    deviceId = device
                }
            }
        }

        // matching{} (not getByName): in the subset variant these source sets appear only
        // once the module declares its targets, after this plugin has applied.
        sourceSets.matching { it.name == "commonTest" }.configureEach {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        sourceSets.matching { it.name == "jvmTest" }.configureEach {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }

    extensions.configure<LibraryExtension> {
        sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
        // 36 (was 34): Room 2.8.x AAR metadata forces a modern compileSdk; kept uniform
        // across store6 modules. v5 modules intentionally stay on 34 (zero v5 change).
        compileSdk = 36

        defaultConfig {
            minSdk = 24
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }

    configureDokka()

    val signingKeyPresent = providers.gradleProperty("signingInMemoryKey").isPresent
    extensions.configure<MavenPublishBaseExtension> {
        configure(
            KotlinMultiplatform(
                javadocJar = JavadocJar.Empty(),
                sourcesJar = true,
                androidVariantsToPublish = listOf("release"),
            ),
        )
        publishToMavenCentral(automaticRelease = true)

        if (signingKeyPresent) {
            signAllPublications()
        }
    }
}

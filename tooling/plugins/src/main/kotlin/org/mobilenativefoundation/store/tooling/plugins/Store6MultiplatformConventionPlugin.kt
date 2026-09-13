package org.mobilenativefoundation.store.tooling.plugins

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** Full-parity store6 module: shared conventions plus the canonical 12 targets. */
class Store6MultiplatformConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.configureStore6Module()
        project.extensions.configure<KotlinMultiplatformExtension> {
            androidTarget()
            jvm()
            iosX64()
            iosArm64()
            iosSimulatorArm64()
            macosArm64()
            watchosArm64()
            tvosArm64()
            linuxX64()
            mingwX64()
            js {
                nodejs()
            }
            @OptIn(ExperimentalWasmDsl::class)
            wasmJs {
                nodejs()
            }
        }
    }
}

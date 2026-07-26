import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform.subset")
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
}

// CMP 1.8.2 gates these pinned targets via project-local properties at task-graph time.
extra["org.jetbrains.compose.experimental.macos.enabled"] = true
extra["org.jetbrains.compose.experimental.jscanvas.enabled"] = true

val store6StabilityConfig =
    rootProject.layout.projectDirectory.file("store6-compose/stability/store6-stability.conf")

composeCompiler {
    stabilityConfigurationFiles.add(store6StabilityConfig)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    inputs.file(store6StabilityConfig).withPathSensitivity(PathSensitivity.RELATIVE)
}

kotlin {
    androidTarget()
    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    js { nodejs() }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.store6Devtools)
                api(compose.runtime)
                api(compose.ui)
                implementation(compose.foundation)
                implementation(compose.material3)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(projects.store6Testing)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val jvmTest by getting {
            dependencies {
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

android {
    namespace = "org.mobilenativefoundation.store6.devtools.compose"
}

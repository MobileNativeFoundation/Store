import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform.subset")
}

kotlin {
    // EXACT paging-common-3.5.1-supported subset: the canonical 12 minus iosX64. androidx.paging
    // dropped Intel targets at 3.4.0-rc01 ("to align with Jetbrains deprecation of the macosX64
    // targets", KT-78660); every other canonical store6 target is published upstream.
    // androidTarget() is mandatory under the subset plugin; spellings must exactly match
    // Store6MultiplatformConventionPlugin's declarations.
    androidTarget()
    jvm()

    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    watchosArm64()
    tvosArm64()

    linuxX64()
    mingwX64()

    js { nodejs() }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { nodejs() } // copy the exact @OptIn(ExperimentalWasmDsl) spelling from
                        // Store6MultiplatformConventionPlugin.kt — the annotation package moved
                        // across Kotlin releases; the full plugin's spelling is the compiling one.

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.core)
                // androidx paging types (PagingSource, LoadParams, LoadResult, PagingState,
                // InvalidatingPagingSourceFactory, RemoteMediator) appear in public signatures.
                api(libs.androidx.paging.common)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(projects.testing)
                // Test-only: MutationStore is exercised as a Store implementation; the production adapter
                // has no dependency on the mutations module.
                implementation(projects.mutations)
                implementation(libs.androidx.paging.testing)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
            }
        }
    }
}

android {
    namespace = "org.mobilenativefoundation.store6.paging"
}

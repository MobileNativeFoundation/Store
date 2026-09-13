import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform.subset")
}

// Writes INSTRUMENTATION_SCOPE_VERSION from the build's VERSION_NAME, so the tracer/meter
// instrumentation-scope version (see OpenTelemetryStoreTelemetry.kt) can never drift from the
// published version; InstrumentationScopeVersionTest asserts the two stay equal. Configuration
// cache safe: the task action reads only its own declared Property/DirectoryProperty inputs,
// never `project`.
@CacheableTask
abstract class GenerateInstrumentationScopeVersion : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val directory = outputDirectory.get().asFile
        directory.mkdirs()
        directory.resolve("InstrumentationScopeVersion.kt").writeText(
            """
            package org.mobilenativefoundation.store6.opentelemetry

            internal const val INSTRUMENTATION_SCOPE_VERSION: String = "${versionName.get()}"

            """.trimIndent() + "\n",
        )
    }
}

// providers.gradleProperty (not findProperty) so this is a declared, config-cache-safe task
// input: the task reruns whenever VERSION_NAME changes instead of caching a stale version.
val generateInstrumentationScopeVersion =
    tasks.register<GenerateInstrumentationScopeVersion>("generateInstrumentationScopeVersion") {
        group = "build"
        description = "Generates INSTRUMENTATION_SCOPE_VERSION from the root VERSION_NAME property."
        versionName.set(providers.gradleProperty("VERSION_NAME"))
        outputDirectory.set(layout.buildDirectory.dir("generated/store6/scopeVersion"))
    }

kotlin {
    // JVM-family subset: this module builds on opentelemetry-java, which publishes JVM
    // bytecode only. androidTarget() is mandatory under the subset plugin. The remaining
    // Store6 targets are additive later via a multiplatform OpenTelemetry API once one is
    // stable; see README "Targets".
    androidTarget()
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.core)
            }
        }
        // Kotlin's hierarchy template has no JVM+Android intermediate; this created source
        // set is compiled per target and gets no metadata compilation, which is what lets it
        // hold a Java-only dependency.
        val jvmAndroidMain by creating {
            dependsOn(commonMain)
            dependencies {
                api(libs.opentelemetry.api)
            }
            // The task-provider form (not a bare path string) so Gradle wires an implicit task
            // dependency: every compilation that consumes this source set's kotlin directories
            // (jvm and androidTarget, both dependsOn this below) runs
            // generateInstrumentationScopeVersion first.
            kotlin.srcDir(generateInstrumentationScopeVersion)
        }
        val jvmMain by getting {
            dependsOn(jvmAndroidMain)
        }
        val androidMain by getting {
            dependsOn(jvmAndroidMain)
        }
        val jvmTest by getting {
            dependencies {
                implementation(projects.testing)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.opentelemetry.sdk.testing)
            }
        }
    }
}

android {
    namespace = "org.mobilenativefoundation.store6.opentelemetry"
}

tasks.withType<Test>().configureEach {
    // The instrumentation-scope version constant must match this module's published version;
    // InstrumentationScopeVersionTest reads this property. VERSION_NAME exists only in the root
    // gradle.properties (RELEASING.md: module gradle.properties files must not reintroduce it),
    // so the root property is the only source — the same provider
    // generateInstrumentationScopeVersion reads for the constant itself.
    systemProperty(
        "store6.opentelemetry.versionName",
        providers.gradleProperty("VERSION_NAME").get(),
    )
}

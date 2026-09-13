import java.util.concurrent.Callable

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

// The Lincheck model-checking budget never fitted a shared test JVM: measured 2h40m-3h13m on
// hosted runners against ~58-59m locally, and a hang that a previous session traced to JVM-state
// instrumentation left over from earlier test classes. It therefore owns a task and a JVM of its
// own (`lincheckTest`), and `jvmTest` never runs it. The full suite is jvmTest + lincheckTest.
val lincheckTestClass = "org.mobilenativefoundation.store6.mutations.MutationJournalLincheckTest"

tasks.named("jvmTest", org.jetbrains.kotlin.gradle.targets.jvm.tasks.KotlinJvmTest::class) {
    filter {
        excludeTestsMatching(lincheckTestClass)
    }
    // The scheduled full-suite workflow and release validation pass -Pstore6.fullJvmSuite.
    if (providers.gradleProperty("store6.fullJvmSuite").isPresent) {
        // Compilation may reuse cached outputs; this test task must execute on every invocation.
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("Full-suite validation requires fresh test execution") { true }
    }
}

// Deliberately absent from `check`, `build` and `jvmTest`: only the sharded full-suite workflow
// lane and a deliberate local invocation run it.
tasks.register("lincheckTest", Test::class) {
    group = org.gradle.language.base.plugins.LifecycleBasePlugin.VERIFICATION_GROUP
    description =
        "Runs $lincheckTestClass in its own JVM. -Pstore6.lincheckShard=k/N runs one shard of the scenario plan."

    // Reuse jvmTest's compiled classes and runtime classpath without depending on the jvmTest
    // TASK: a provider derived from a TaskProvider carries that task as a dependency, which would
    // drag the whole suite into every shard job. A Callable defers to the resolved file
    // collections, whose only producers are the compile and resource tasks.
    //
    // Trade-off, deliberate: resolving the TaskProvider inside the Callable is a Task-at-execution
    // reference, which the Gradle configuration cache forbids — it would report "invocation of
    // Task.project/another task at execution time". That costs nothing today, because this build
    // does not set org.gradle.configuration-cache (see gradle.properties) and the release lanes run
    // without it. Whoever turns the configuration cache on must replace both lines with the
    // configuration-time values (e.g. the jvmTest compilation's output and runtimeDependencyFiles
    // from the Kotlin JVM target) rather than reintroducing jvmTest.map { … }, which would put the
    // whole suite back into every shard.
    val jvmTest = tasks.named("jvmTest", Test::class)
    testClassesDirs = files(Callable { jvmTest.get().testClassesDirs })
    classpath = files(Callable { jvmTest.get().classpath })
    dependsOn("jvmTestClasses")

    filter {
        includeTestsMatching(lincheckTestClass)
    }

    // The evidence recorder parses the executed scenario indices out of the Gradle log.
    testLogging {
        showStandardStreams = true
    }

    val shard = providers.gradleProperty("store6.lincheckShard")
    if (shard.isPresent) {
        systemProperty("store6.lincheckShard", shard.get())
    }

    // Sharding only helps if every invocation really executes.
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Lincheck shards must execute on every invocation") { true }
}

// Kover's on-the-fly agent must not attach to the Lincheck JVM: Lincheck performs its own bytecode
// transformation, and an agent conflict there is indistinguishable from a model-checking hang.
// The jvmTest counterpart of this exclusion lives in Store6Conventions.
extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
    currentProject {
        instrumentation {
            disabledForTestTasks.add("lincheckTest")
        }
    }
}

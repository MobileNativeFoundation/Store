plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.allopen") version libs.versions.baseKotlin.get()
    alias(libs.plugins.kotlinx.benchmark)
}

kotlin { jvmToolchain(11) }

// JMH requires @State classes to be non-final. The kotlinx.benchmark annotations typealias to
// JMH's on the JVM target, so allopen keys on the JMH FQN (kotlinx-benchmark README, Kotlin/JVM
// setup). Benchmark classes are additionally declared `open` for clarity.
allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

dependencies {
    implementation(projects.core)
    // FakeSourceOfTruth: the shared, contract-kit-passing SoT on BOTH sides of every ratio.
    implementation(projects.testing)
    implementation(libs.kotlinx.benchmark.runtime)
    testImplementation(kotlin("test"))
}

benchmark {
    configurations {
        named("main") {
            warmups = 5
            iterations = 10
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "avgt"
            outputTimeUnit = "us"
        }
        // Fast, report-only signal; never a hard performance gate.
        register("smoke") {
            warmups = 2
            iterations = 3
            iterationTime = 500
            iterationTimeUnit = "ms"
            mode = "avgt"
            outputTimeUnit = "us"
        }
        // Longer calibration profile for an otherwise quiet machine.
        register("calibrate") {
            warmups = 8
            iterations = 15
            iterationTime = 2
            iterationTimeUnit = "s"
            mode = "avgt"
            outputTimeUnit = "us"
            advanced("jvmForks", "3")
        }
    }
    targets {
        register("main") {
            this as kotlinx.benchmark.gradle.JvmBenchmarkTarget
            // PIN: JMH backend pinned for reproducibility.
            jmhVersion = "1.37"
        }
    }
}

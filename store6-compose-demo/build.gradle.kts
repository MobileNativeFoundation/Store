plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
}

kotlin { jvmToolchain(11) }

val store6StabilityConfig =
    rootProject.layout.projectDirectory.file("store6-compose/stability/store6-stability.conf")

composeCompiler {
    stabilityConfigurationFiles.add(store6StabilityConfig)
    metricsDestination.set(layout.buildDirectory.dir("compose-metrics"))
    reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
}

// The CI compose-stability gate reads the reports emitted below, so they must always describe
// the current sources and conf. Two Compose-plugin gaps otherwise break that:
//   1. stabilityConfigurationFiles is not registered as a task input, so a conf-only edit leaves
//      compileKotlin UP-TO-DATE against stale settings — fixed by inputs.file below.
//   2. the reports are an undeclared side-effect output, so a build-cache hit (org.gradle.caching
//      is on, and CI restores the cache) skips the compiler and leaves whatever report happens to
//      be on disk — which would make the gate assert against a stale file. Opting these tiny
//      compilations out of the cache keeps report-on-disk == inputs-on-disk; UP-TO-DATE still
//      applies, and an up-to-date task's report was written by the last real execution with
//      exactly these inputs.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    inputs.file(store6StabilityConfig).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.cacheIf { false }
}

dependencies {
    implementation(projects.store6Compose)
    implementation(projects.store6Testing)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application { mainClass = "org.mobilenativefoundation.store6.composedemo.MainKt" }
}

plugins {
    // Version-less: Kover is on the root buildscript classpath (catalog `kover`),
    // and a versioned plugins-block request against that classloader cannot
    // resolve ("already on the classpath with an unknown version").
    id("org.jetbrains.kotlinx.kover")
}

// Merges JVM coverage across the store6 production library modules — every module
// that applies the store6 conventions — into one JaCoCo-compatible XML
// (build/reports/kover/report.xml) for the Codecov upload in ci.yml (flag: store6).
// Quickstarts, samples, demos, benchmarks, the extension probe, swift dumps, the
// SPM facade, and the BOM are exercisers or metadata, not library surface, and are
// deliberately absent. Kover covers JVM-target tests only; native/JS runs are
// exercised by CI but contribute no coverage data. This module publishes nothing
// and must stay out of the publish allowlist in ci.yml.
dependencies {
    kover(projects.core)
    kover(projects.testing)
    kover(projects.sqldelight)
    kover(projects.compose)
    kover(projects.room)
    kover(projects.pagingAndroidx)
    kover(projects.graphql)
    kover(projects.ktor)
    kover(projects.realtime)
    kover(projects.file)
    kover(projects.opentelemetry)
    kover(projects.devtools)
    kover(projects.devtoolsInspector)
    kover(projects.mutations)
    kover(projects.mutationsTesting)
    kover(projects.mutationsSqldelight)
    kover(projects.mutationsDrain)
    kover(projects.mutationsDrainMeeseeks)
    kover(projects.mutationsConflicts)
}

// DRAFT — new file at swift-dumps/mutations-skie/build.gradle.kts
// Modeled on swift-dumps/skie/build.gradle.kts; deltas are the store6SwiftDump block,
// the framework identity, the explicit transitiveExport = false, and the mutations dependency.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.skie)
    id("org.mobilenativefoundation.store.store6.swift-dump.skie")
}

store6SwiftDump {
    surfaceName.set("mutations")
    frameworkName.set("Store6MutationsSkie")
    outputHeaderName.set("Store6MutationsSkie.h")
    outputSwiftName.set("Store6MutationsSkie.swift")
    committedDumpPath.set("mutations/api/swift/skie")
}

kotlin {
    iosArm64 {
        binaries.framework {
            baseName = "Store6MutationsSkie"
            // R-2b=A: mutations is the sole direct export root. Core symbols surface only as
            // referenced classifiers through the api(:mutations) dependency; exporting
            // :core here would broaden the reviewed root-module surface.
            transitiveExport = false
            export(project(":mutations"))
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":mutations"))
            }
        }
    }
}

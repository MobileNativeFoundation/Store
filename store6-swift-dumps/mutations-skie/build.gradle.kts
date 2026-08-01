// DRAFT — new file at store6-swift-dumps/mutations-skie/build.gradle.kts
// Modeled on store6-swift-dumps/skie/build.gradle.kts; deltas are the store6SwiftDump block,
// the framework identity, the explicit transitiveExport = false, and the mutations dependency.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.skie)
    id("org.mobilenativefoundation.store.store6.swift-dump.skie")
}

store6SwiftDump {
    surfaceName.set("store6-mutations")
    frameworkName.set("Store6MutationsSkie")
    outputHeaderName.set("Store6MutationsSkie.h")
    outputSwiftName.set("Store6MutationsSkie.swift")
    committedDumpPath.set("store6-mutations/api/swift/skie")
}

kotlin {
    iosArm64 {
        binaries.framework {
            baseName = "Store6MutationsSkie"
            // R-2b=A: mutations is the sole direct export root. Core symbols surface only as
            // referenced classifiers through the api(:store6-mutations) dependency; exporting
            // :store6-core here would broaden the reviewed root-module surface.
            transitiveExport = false
            export(project(":store6-mutations"))
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":store6-mutations"))
            }
        }
    }
}

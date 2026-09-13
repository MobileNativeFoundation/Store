// DRAFT — new file at swift-dumps/mutations-objc/build.gradle.kts
// Modeled on swift-dumps/objc/build.gradle.kts; deltas are the store6SwiftDump block,
// the framework identity, the explicit transitiveExport = false, and the mutations dependency.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.mobilenativefoundation.store.store6.swift-dump.objc")
}

store6SwiftDump {
    surfaceName.set("mutations")
    frameworkName.set("Store6Mutations")
    outputHeaderName.set("Store6Mutations.h")
    committedDumpPath.set("mutations/api/swift/objc")
}

kotlin {
    iosArm64 {
        binaries.framework {
            baseName = "Store6Mutations"
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

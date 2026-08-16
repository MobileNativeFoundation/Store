plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.mobilenativefoundation.store.store6.swift-dump.objc")
}

kotlin {
    iosArm64 {
        binaries.framework {
            baseName = "Store6Core"
            export(project(":store6-core"))
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":store6-core"))
            }
        }
    }
}

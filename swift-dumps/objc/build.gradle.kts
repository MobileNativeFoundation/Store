plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.mobilenativefoundation.store.store6.swift-dump.objc")
}

kotlin {
    iosArm64 {
        binaries.framework {
            baseName = "Store6Core"
            export(project(":core"))
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":core"))
            }
        }
    }
}

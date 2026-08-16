plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.skie)
    id("org.mobilenativefoundation.store.store6.swift-dump.skie")
}

kotlin {
    iosArm64 {
        binaries.framework {
            baseName = "Store6CoreSkie"
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

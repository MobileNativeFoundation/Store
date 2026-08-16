plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.skie)
    id("org.mobilenativefoundation.store.store6.swift-dump.skie")
}

kotlin {
    iosArm64 {
        binaries.framework {
            baseName = "Store6CoreSkie"
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

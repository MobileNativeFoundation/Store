import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("com.chromaticnoise.multiplatform-swiftpackage") version "2.0.3"
    id("org.jetbrains.kotlin.native.cocoapods")
    id("app.cash.zipline")
}

multiplatformSwiftPackage {
    packageName("Store")
    swiftToolsVersion("5.3")
    targetPlatforms {
        iOS { v("13") }
    }
    outputDirectory(File(projectDir, "swift/package"))
}

kotlin {
    android()
    jvm()
    val xcf = XCFramework("store")
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "store"
            xcf.add(this)
        }
    }

    cocoapods {
        summary = "Store"
        homepage = "https://github.com/dropbox/store"
        version = "1.6.20"
    }

    sourceSets {
        val commonMain by getting {
            dependencies {

                with(Deps.Kotlinx) {
                    api(coroutinesCore)
                    api(serializationCore)
                    api(serializationJson)
                    api(dateTime)
                }

                with(Deps.Cash.Zipline) {
                    implementation(zipline)
                }

                with(Deps.Ktor) {
                    implementation(clientCore)
                    implementation(clientJson)
                    implementation(clientSerialization)
                    implementation(contentNegotiation)
                    implementation(json)
                    implementation(clientLogging)
                }

                with(Deps.Stately) {
                    implementation(common)
                    implementation(concurrency)
                    implementation(isolate)
                    implementation(collections)
                }
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                with(Deps.Kotlinx) {
                    implementation(coroutinesTest)
                }
            }
        }

        val androidMain by getting {
            dependencies {
                with(Deps.AndroidX) {
                    api(appCompat)
                    api(coreKtx)
                }
            }
        }

        val jvmMain by getting

        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependsOn(commonMain)

            dependencies {
                implementation(Deps.Ktor.clientDarwin)
            }

            iosX64Main.dependsOn(this)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
        }
    }
}

android {
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")

    compileSdk = Version.androidCompileSdk
}
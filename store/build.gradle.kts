@file:Suppress("UnstableApiUsage")

import com.vanniktech.maven.publish.JavadocJar.Dokka
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("com.vanniktech.maven.publish.base")
    id("org.jetbrains.dokka")
    id("org.jetbrains.kotlin.native.cocoapods")
    id("com.chromaticnoise.multiplatform-swiftpackage") version "2.0.3"
    id("org.jetbrains.kotlinx.kover")
}

multiplatformSwiftPackage {
    packageName("Store5")
    swiftToolsVersion("5.3")
    targetPlatforms {
        iOS { v("13") }
    }
    outputDirectory(File(projectDir, "swift/package"))
}

kotlin {
    android()
    jvm()

    val xcf = XCFramework("Store5")
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "Store5"
            xcf.add(this)
        }
    }

    cocoapods {
        summary = "Store5"
        homepage = "https://github.com/MobileNativeFoundation/Store"
        version = "5.0.0-alpha01"
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                with(Deps.Kotlinx) {
                    implementation(coroutinesCore)
                    implementation(serializationCore)
                    implementation(serializationJson)
                    implementation(dateTime)
                }

                with(Deps.Stately) {
                    implementation(isolate)
                    implementation(collections)
                }

            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlin:kotlin-stdlib:1.7.10")
                implementation(Deps.Kotlinx.coroutinesCore)
                with(Deps.Test) {
                    implementation(junit)
                    implementation(core)
                    implementation(coroutinesTest)
                }
            }
        }

        val jvmMain by getting

        val androidMain by getting

        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependsOn(commonMain)
            iosX64Main.dependsOn(this)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
        }
    }
}

android {
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    compileSdk = 31

    lint {
        disable += "ComposableModifierFactory"
        disable += "ModifierFactoryExtensionFunction"
        disable += "ModifierFactoryReturnType"
        disable += "ModifierFactoryUnreferencedReceiver"
    }
}

tasks.withType<DokkaTask>().configureEach {
    dokkaSourceSets.configureEach {
        reportUndocumented.set(false)
        skipDeprecated.set(true)
        jdkVersion.set(8)
    }
}

configure<MavenPublishBaseExtension> {
    configure(
        KotlinMultiplatform(javadocJar = Dokka("dokkaGfm"))
    )
}
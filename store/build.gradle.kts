@file:Suppress("UnstableApiUsage")

import com.vanniktech.maven.publish.SonatypeHost.S01
import org.jetbrains.dokka.gradle.DokkaTask

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.dokka")
    id("org.jetbrains.kotlinx.kover")
    id("co.touchlab.faktory.kmmbridge") version Version.kmmBridge
    `maven-publish`
    kotlin("native.cocoapods")
    id("kotlinx-atomicfu")
}

kotlin {
    android()
    jvm()
    iosArm64()
    iosX64()
    js {
        browser()
        nodejs()
    }
    cocoapods {
        summary = "Store5"
        homepage = "https://github.com/MobileNativeFoundation/Store"
        ios.deploymentTarget = "13"
        version = Version.store
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                with(Deps.Kotlinx) {
                    implementation(coroutinesCore)
                    implementation(dateTime)
                    implementation(project(":multicast"))
                    implementation(project(":cache"))
                }
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(Deps.Kotlinx.stdLib)
                implementation(Deps.Kotlinx.coroutinesCore)
                implementation(Deps.Kotlinx.serializationCore)
                with(Deps.Test) {
                    implementation(junit)
                    implementation(core)
                    implementation(coroutinesTest)
                }
            }
        }

        val jvmMain by getting
        val androidMain by getting
        val nativeMain by creating {
            dependsOn(commonMain)
        }
    }
}

android {
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    compileSdk = 31

    defaultConfig {
        minSdk = 24
        targetSdk = 31
    }

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

mavenPublishing {
    publishToMavenCentral(S01)
    signAllPublications()
}

addGithubPackagesRepository()
kmmbridge {
    githubReleaseArtifacts()
    githubReleaseVersions()
    versionPrefix.set("5.0.0-alpha")
    spm()
}

koverMerged {
    enable()

    xmlReport {
        onCheck.set(true)
        reportFile.set(layout.projectDirectory.file("kover/coverage.xml"))
    }

    htmlReport {
        onCheck.set(true)
        reportDir.set(layout.projectDirectory.dir("kover/html"))
    }

    verify {
        onCheck.set(true)
    }
}

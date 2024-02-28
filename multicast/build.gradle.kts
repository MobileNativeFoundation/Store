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
    `maven-publish`
    kotlin("native.cocoapods")
    id("kotlinx-atomicfu")
}

kotlin {
    android()
    jvm()
    iosArm64()
    iosX64()
    linuxX64()
    iosSimulatorArm64()
    js {
        browser()
        nodejs()
    }
    cocoapods {
        summary = "Multicast5"
        homepage = "https://github.com/MobileNativeFoundation/Store"
        ios.deploymentTarget = "13"
        version = libs.versions.store.get()
    }

    sourceSets {
        all {
            languageSettings.apply {
                optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
                optIn("kotlin.RequiresOptIn")
            }
        }

        val commonMain by getting {
            dependencies {
                api(libs.kotlinx.atomic.fu)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val jvmMain by getting
        val androidMain by getting
        val nativeMain by creating {
            dependsOn(commonMain)
        }
    }

    jvmToolchain(11)
}

android {
    namespace = "org.mobilenativefoundation.store.multicast5"

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    compileSdk = 33

    defaultConfig {
        minSdk = 24
        targetSdk = 33
    }

    lint {
        disable += "ComposableModifierFactory"
        disable += "ModifierFactoryExtensionFunction"
        disable += "ModifierFactoryReturnType"
        disable += "ModifierFactoryUnreferencedReceiver"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
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

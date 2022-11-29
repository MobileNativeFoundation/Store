@file:Suppress("UnstableApiUsage")

import com.android.build.gradle.internal.scope.ProjectInfo.Companion.getBaseName
import com.vanniktech.maven.publish.JavadocJar.Dokka
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost.S01
import org.jetbrains.dokka.gradle.DokkaTask

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("com.vanniktech.maven.publish.base")
    id("org.jetbrains.dokka")
    id("org.jetbrains.kotlinx.kover")
    id("co.touchlab.faktory.kmmbridge") version Version.kmmBridge
    `maven-publish`
    kotlin("native.cocoapods")
}

kotlin {
    android()
    jvm()
    ios()
    iosSimulatorArm64()
    cocoapods {
        summary = "Store5"
        homepage = "https://github.com/MobileNativeFoundation/Store"
        ios.deploymentTarget = "13"
        version = "5.0.0-alpha01"
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                with(Deps.Kotlinx) {
                    implementation(coroutinesCore)
                    implementation(dateTime)
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
        val iosMain by getting
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

mavenPublishing {
    publishToMavenCentral(S01)
    signAllPublications()
}

addGithubPackagesRepository()
kmmbridge {
    githubReleaseArtifacts()
    githubReleaseVersions()
    versionPrefix.set("5.0.0-alpha0")
    spm()
}
import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.dokka.gradle.DokkaTask

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.dokka")
    id("co.touchlab.faktory.kmmbridge")
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
        summary = "Store5/Paging"
        homepage = "https://github.com/MobileNativeFoundation/Store"
        ios.deploymentTarget = "13"
        version = libs.versions.store.get()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(project(":store"))
                implementation(project(":cache"))
                api(project(":core"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        val androidMain by getting
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.turbine)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }

    jvmToolchain(11)
}

android {
    namespace = "org.mobilenativefoundation.store.paging5"

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34
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
        jdkVersion.set(11)
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.S01)
    signAllPublications()
}

addGithubPackagesRepository()
kmmbridge {
    githubReleaseArtifacts()
    githubReleaseVersions()
    versionPrefix.set(libs.versions.store.get())
    spm()
}

atomicfu {
    transformJvm = false
    transformJs = false
}

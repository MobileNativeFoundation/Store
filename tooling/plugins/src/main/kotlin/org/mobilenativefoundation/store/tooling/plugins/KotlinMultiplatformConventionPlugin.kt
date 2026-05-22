@file:Suppress("UnstableApiUsage")

package org.mobilenativefoundation.store.tooling.plugins

import addGithubPackagesRepository
import co.touchlab.faktory.KmmBridgeExtension
import com.android.build.api.dsl.LibraryExtension
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import kotlinx.atomicfu.plugin.gradle.AtomicFUPluginExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.cocoapods.CocoapodsExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

class KotlinMultiplatformConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
        val jvmToolchainVersion = versionCatalog.jvmToolchainVersion

        with(pluginManager) {
            apply("org.jetbrains.kotlin.multiplatform")
            apply("org.jetbrains.kotlin.plugin.serialization")
            apply("com.android.library")
            apply("com.vanniktech.maven.publish")
            apply("org.jetbrains.dokka")
            apply("co.touchlab.faktory.kmmbridge")
            apply("maven-publish")
            apply("org.jetbrains.kotlin.native.cocoapods")
            apply("org.jetbrains.kotlinx.atomicfu")
            apply("org.jetbrains.kotlinx.binary-compatibility-validator")
        }

        extensions.configure<KotlinMultiplatformExtension> {
            applyDefaultHierarchyTemplate()

            androidTarget()

            jvm()

            iosX64()
            iosArm64()
            iosSimulatorArm64()

            linuxX64()

            js {
                browser()
                nodejs()
            }

            @OptIn(ExperimentalWasmDsl::class)
            wasmJs {
                browser()
                nodejs()
            }

            jvmToolchain(jvmToolchainVersion.toInt())

            targets.all {
                compilations.all {
                    compileTaskProvider.configure {
                        compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
                    }
                }
            }

            targets.withType<KotlinNativeTarget>().configureEach {
                compilations.configureEach {
                    compileTaskProvider.configure {
                        compilerOptions {
                            freeCompilerArgs.add("-Xallocator=custom")
                            freeCompilerArgs.add("-XXLanguage:+ImplicitSignedToUnsignedIntegerConversion")
                            freeCompilerArgs.add("-Xadd-light-debug=enable")

                            freeCompilerArgs.addAll(
                                "-opt-in=kotlin.RequiresOptIn",
                                "-opt-in=kotlin.time.ExperimentalTime",
                                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                                "-opt-in=kotlinx.coroutines.FlowPreview",
                                "-opt-in=kotlinx.cinterop.ExperimentalForeignApi",
                                "-opt-in=kotlinx.cinterop.BetaInteropApi",
                            )
                        }
                    }
                }
            }

            sourceSets.all {
                languageSettings.apply {
                    optIn("kotlin.contracts.ExperimentalContracts")
                    optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
                    optIn("kotlin.RequiresOptIn")
                }
            }

            sourceSets.getByName("commonTest") { dependencies { implementation(kotlin("test")) } }

            sourceSets.getByName("jvmTest") { dependencies { implementation(kotlin("test-junit")) } }

            val atomicFuDep = versionCatalog.findLibrary("kotlinx-atomic-fu").get().get()
            sourceSets.getByName("nativeMain") { dependencies { api(atomicFuDep) } }
            sourceSets.getByName("jsMain") { dependencies { api(atomicFuDep) } }
            sourceSets.getByName("wasmJsMain") { dependencies { api(atomicFuDep) } }

            configureCocoapods(project.versionCatalog.store)
        }

        configureMultiplatformKotlin()
        configureAndroid()
        configureDokka()
        configureMavenPublishing()
        addGithubPackagesRepository()
        configureKmmBridge()
        configureAtomicFu()
    }
}

fun Project.configureMultiplatformKotlin() {
    val jvmCompatVersion = versionCatalog.jvmCompatVersion
    extensions.configure<KotlinMultiplatformExtension> {
        targets.configureEach {
            compilations.configureEach {
                compileTaskProvider.configure {
                    compilerOptions {
                        if (this is org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions) {
                            jvmTarget.set(JvmTarget.fromTarget(jvmCompatVersion))
                        }
                    }
                }
            }
        }
    }
    configureJava()
}

fun Project.configureJava() {
    java {
        toolchain { languageVersion.set(JavaLanguageVersion.of(versionCatalog.jvmToolchainVersion)) }
        val jvmCompatVersion = JavaVersion.toVersion(versionCatalog.jvmCompatVersion)
        sourceCompatibility = jvmCompatVersion
        targetCompatibility = jvmCompatVersion
    }
}

fun Project.configureAndroid() {
    android {
        sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
        compileSdk = versionCatalog.androidCompileSdk.toInt()
        defaultConfig { minSdk = versionCatalog.androidMinSdk.toInt() }
        val targetSdkVersion = versionCatalog.androidTargetSdk.toInt()
        lint {
            disable += "ComposableModifierFactory"
            disable += "ModifierFactoryExtensionFunction"
            disable += "ModifierFactoryReturnType"
            disable += "ModifierFactoryUnreferencedReceiver"
            targetSdk = targetSdkVersion
        }
        testOptions { targetSdk = targetSdkVersion }
        val jvmCompatVersion = JavaVersion.toVersion(versionCatalog.jvmCompatVersion)
        compileOptions {
            sourceCompatibility = jvmCompatVersion
            targetCompatibility = jvmCompatVersion
        }
    }
}

fun Project.android(action: LibraryExtension.() -> Unit) = extensions.configure<LibraryExtension>(action)

private fun Project.java(action: JavaPluginExtension.() -> Unit) = extensions.configure<JavaPluginExtension>(action)

fun Project.configureMavenPublishing() =
    extensions.configure<MavenPublishBaseExtension> {
        publishToMavenCentral(automaticRelease = true)
        signAllPublications()
    }

fun Project.configureKmmBridge() =
    extensions.configure<KmmBridgeExtension> {
        githubReleaseArtifacts()
        githubReleaseVersions()
        versionPrefix.set(versionCatalog.store)
        spm()
    }

fun Project.configureAtomicFu() = extensions.configure<AtomicFUPluginExtension> { transformJvm = false }

fun Project.configureDokka() {
    extensions.configure<DokkaExtension> {
        dokkaSourceSets.configureEach {
            reportUndocumented.set(false)
            skipDeprecated.set(true)
            jdkVersion.set(versionCatalog.jvmCompatVersion.toInt())
        }
    }
}

fun Project.android(name: String) {
    android { namespace = "org.mobilenativefoundation.store.$name" }
}

fun KotlinMultiplatformExtension.configureCocoapods(storeVersion: String) {
    (this as ExtensionAware).extensions.configure(CocoapodsExtension::class.java) {
        version = storeVersion
    }
}

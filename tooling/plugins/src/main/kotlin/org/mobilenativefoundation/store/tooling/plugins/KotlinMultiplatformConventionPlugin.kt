@file:Suppress("UnstableApiUsage")

package org.mobilenativefoundation.store.tooling.plugins

import co.touchlab.kmmbridge.KmmBridgeExtension
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import gitHubReleaseArtifacts
import kotlinx.atomicfu.plugin.gradle.AtomicFUPluginExtension
import org.gradle.api.Action
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.cocoapods.CocoapodsExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

class KotlinMultiplatformConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
        val jvmToolchainVersion = versionCatalog.jvmToolchainVersion

        // Single source of truth for the published version: gradle/libs.versions.toml (store).
        // vanniktech maven-publish falls back to project.version when VERSION_NAME is absent.
        version = versionCatalog.store

        with(pluginManager) {
            apply("org.jetbrains.kotlin.multiplatform")
            apply("org.jetbrains.kotlin.plugin.serialization")
            apply("com.android.kotlin.multiplatform.library")
            apply("com.vanniktech.maven.publish")
            apply("org.jetbrains.dokka")
            apply("co.touchlab.kmmbridge.github")
            apply("maven-publish")
            apply("org.jetbrains.kotlin.native.cocoapods")
            apply("org.jetbrains.kotlinx.atomicfu")
            apply("org.jetbrains.kotlinx.binary-compatibility-validator")
            apply("org.mobilenativefoundation.store.formatting")
        }

        extensions.configure<KotlinMultiplatformExtension> {
            applyDefaultHierarchyTemplate()

            context(this, this@with) {
                configureAndroid()
            }

            jvm()

            iosX64()
            iosArm64()
            iosSimulatorArm64()

            linuxX64()

            js {
                browser {
                    testTask {
                        useKarma {
                            useChromeHeadless()
                        }
                        // Karma uses Mocha under the hood for the test framework
                        useMocha {
                            timeout = "5s"
                        }
                    }
                }
                nodejs {
                    testTask {
                        useMocha {
                            timeout = "5s"
                        }
                    }
                }
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

            configureCocoapods(project.versionCatalog.store)
        }

        configureMultiplatformKotlin()
        configureDokka()
        configureMavenPublishing()
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
                        if (this is KotlinJvmCompilerOptions) {
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

context(ext: KotlinMultiplatformExtension, project: Project)
fun configureAndroid() {
    ext.android {
        namespace = "org.mobilenativefoundation.store.${project.name}"

        compileSdk = project.versionCatalog.androidCompileSdk.toInt()
        minSdk = project.versionCatalog.androidMinSdk.toInt()
        val targetSdkVersion = project.versionCatalog.androidTargetSdk.toInt()
        lint {
            disable += "ComposableModifierFactory"
            disable += "ModifierFactoryExtensionFunction"
            disable += "ModifierFactoryReturnType"
            disable += "ModifierFactoryUnreferencedReceiver"
            targetSdk = targetSdkVersion
        }
        withHostTest {
            targetSdk {
                version = release(targetSdkVersion)
            }
        }
        withDeviceTest {
            targetSdk {
                version = release(targetSdkVersion)
            }
        }
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(project.versionCatalog.jvmCompatVersion))
        }
    }
}

fun KotlinMultiplatformExtension.android(configure: Action<KotlinMultiplatformAndroidLibraryTarget>): Unit =
    (this as ExtensionAware).extensions.configure("android", configure)

private fun Project.java(action: JavaPluginExtension.() -> Unit) = extensions.configure<JavaPluginExtension>(action)

fun Project.configureMavenPublishing() =
    extensions.configure<MavenPublishBaseExtension> {
        publishToMavenCentral(automaticRelease = true)
        signAllPublications()
    }

fun Project.configureKmmBridge() =
    extensions.configure<KmmBridgeExtension> {
        gitHubReleaseArtifacts()
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

fun KotlinMultiplatformExtension.configureCocoapods(storeVersion: String) {
    (this as ExtensionAware).extensions.configure(CocoapodsExtension::class.java) {
        version = storeVersion
    }
}

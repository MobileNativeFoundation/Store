@file:Suppress("UnstableApiUsage")

package org.mobilenativefoundation.store.tooling.plugins

import com.android.build.api.dsl.LibraryExtension
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import kotlinx.atomicfu.plugin.gradle.AtomicFUPluginExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.withType
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

class KotlinMultiplatformConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {


        with(pluginManager) {
            apply("org.jetbrains.kotlin.multiplatform")
            apply("org.jetbrains.kotlin.plugin.serialization")
            apply("com.android.library")
            apply("com.vanniktech.maven.publish")
            apply("org.jetbrains.dokka")
            apply("maven-publish")
            apply("kotlinx-atomicfu")
            apply("org.jetbrains.kotlinx.binary-compatibility-validator")
        }


        extensions.configure<KotlinMultiplatformExtension> {

            androidTarget()
            applyDefaultHierarchyTemplate()

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
                nodejs()
            }

            jvmToolchain(11)

            targets.all {
                compilations.all {
                    compilerOptions.configure {
                        freeCompilerArgs.add("-Xexpect-actual-classes")
                    }
                }
            }

            targets.withType<KotlinNativeTarget>().configureEach {
                compilations.configureEach {
                    compilerOptions.configure {
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

            sourceSets.all {
                languageSettings.apply {
                    optIn("kotlin.contracts.ExperimentalContracts")
                    optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
                    optIn("kotlin.RequiresOptIn")
                }
            }

            sourceSets.getByName("commonTest") {
                dependencies {
                    implementation(kotlin("test"))
                }
            }

            sourceSets.getByName("jvmTest") {
                dependencies {
                    implementation(kotlin("test-junit"))
                }
            }

            sourceSets.getByName("nativeMain") {
                dependsOn(sourceSets.getByName("commonMain"))
            }


        }

        configureKotlin()
        configureAndroid()
        configureDokka()
        configureMavenPublishing()
        configureAtomicFu()
    }
}


fun Project.configureKotlin() {
    configureJava()
}

fun Project.configureJava() {
    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(11))
        }
    }
}

fun Project.configureAndroid() {
    android {
        namespace = "org.mobilenativefoundation.store.$name"
        sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
        compileSdk = Versions.COMPILE_SDK
        defaultConfig {
            minSdk = Versions.MIN_SDK
            targetSdk = Versions.TARGET_SDK
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
}


fun Project.android(action: LibraryExtension.() -> Unit) = extensions.configure<LibraryExtension>(action)

private fun Project.java(action: JavaPluginExtension.() -> Unit) = extensions.configure<JavaPluginExtension>(action)


object Versions {
    const val COMPILE_SDK = 34
    const val MIN_SDK = 24
    const val TARGET_SDK = 34
    const val STORE = "5.1.0-alpha07"
}


fun Project.configureMavenPublishing() = extensions.configure<MavenPublishBaseExtension> {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
}


fun Project.configureAtomicFu() =
    extensions.configure<AtomicFUPluginExtension> {
        transformJvm = false
        transformJs = false
    }

fun Project.configureDokka() = tasks.withType<DokkaTask>().configureEach {
    dokkaSourceSets.configureEach {
        reportUndocumented.set(false)
        skipDeprecated.set(true)
        jdkVersion.set(11)
    }
}
@file:Suppress("UnstableApiUsage")

package org.mobilenativefoundation.store.tooling.plugins

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

class AndroidConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        with(pluginManager) {
            apply("com.android.library")
            apply("com.vanniktech.maven.publish")
            apply("org.jetbrains.dokka")
            apply("maven-publish")
            apply("org.jetbrains.kotlinx.binary-compatibility-validator")
            apply("org.mobilenativefoundation.store.formatting")
        }

        extensions.configure<LibraryExtension> {
            compileSdk = versionCatalog.androidCompileSdk.toInt()

            defaultConfig {
                minSdk = versionCatalog.androidMinSdk.toInt()
            }

            val targetSdkVersion = versionCatalog.androidTargetSdk.toInt()

            lint {
                disable += "ComposableModifierFactory"
                disable += "ModifierFactoryExtensionFunction"
                disable += "ModifierFactoryReturnType"
                disable += "ModifierFactoryUnreferencedReceiver"
                targetSdk = targetSdkVersion
            }

            testOptions {
                targetSdk = targetSdkVersion
            }

            compileOptions {
                val jvmCompatVersion = JavaVersion.toVersion(versionCatalog.jvmCompatVersion)
                sourceCompatibility = jvmCompatVersion
                targetCompatibility = jvmCompatVersion
            }
        }

        configureAndroidKotlin()
        configureDokka()
        configureMavenPublishing()
    }
}

fun Project.configureAndroidKotlin() {
    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(versionCatalog.jvmCompatVersion))
        }
    }
    configureJava()
}

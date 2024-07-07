@file:Suppress("UnstableApiUsage")

package org.mobilenativefoundation.store.tooling.plugins

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        with(pluginManager) {
            apply("org.jetbrains.kotlin.android")
            apply("com.android.library")
            apply("com.vanniktech.maven.publish")
            apply("org.jetbrains.dokka")
            apply("org.jetbrains.kotlinx.kover")
            apply("maven-publish")
            apply("org.jetbrains.kotlin.native.cocoapods")
            apply("org.jetbrains.kotlinx.binary-compatibility-validator")
        }


        extensions.configure<LibraryExtension> {

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

        configureKotlin()
        configureDokka()
        configureMavenPublishing()
    }
}




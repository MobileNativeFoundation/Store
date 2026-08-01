@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

plugins {
    alias(libs.plugins.ktlint)
    id("com.diffplug.spotless") version "6.4.1"
    // 010/011/012 toolchain: loaded once here (apply false) so every module shares one
    // plugin classloader + version; module branches only `alias(...)` without versions.
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.room3) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
    alias(libs.plugins.jetbrains.compose) apply false
    alias(libs.plugins.kotlinx.benchmark) apply false
}

buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }

    dependencies {
        classpath(libs.android.gradle.plugin)
        classpath(libs.kotlin.gradle.plugin)
        classpath(libs.kotlin.serialization.plugin)
        classpath(libs.dokka.gradle.plugin)
        classpath(libs.ktlint.gradle.plugin)
        classpath(libs.jacoco.gradle.plugin)
        classpath(libs.maven.publish.plugin)
        classpath(libs.atomic.fu.gradle.plugin)
        classpath(libs.kmmBridge.gradle.plugin)
        classpath(libs.binary.compatibility.validator)
    }
}

allprojects {
    repositories {
        mavenCentral()
        google()
    }
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
        compilerOptions.jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.DISABLE)
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
            targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
                binaries.withType<org.jetbrains.kotlin.gradle.plugin.mpp.Framework>().configureEach {
                    // Kotlin 2.2.20+ exports KDoc by default; preserve the frozen Swift dumps.
                    exportKdoc.set(false)
                }
            }
        }
    }

    if (name.startsWith("store6")) return@subprojects

    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "com.diffplug.spotless")

    ktlint {
        disabledRules.add("import-ordering")
    }

    spotless {
        kotlin {
            target("src/**/*.kt")
        }
    }
}

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile> {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }

    withType<JavaCompile>().configureEach {
        sourceCompatibility = JavaVersion.VERSION_11.name
        targetCompatibility = JavaVersion.VERSION_11.name
    }
}

// Workaround for https://youtrack.jetbrains.com/issue/KT-62040
tasks.getByName("wrapper")

tasks.register("refreshSwiftDumps") {
    dependsOn(
        ":store6-swift-dumps-objc:refreshSwiftDump",
        ":store6-swift-dumps-skie:refreshSwiftDump",
        ":store6-swift-dumps-mutations-objc:refreshSwiftDump",
        ":store6-swift-dumps-mutations-skie:refreshSwiftDump",
    )
}

tasks.register("checkSwiftDumps") {
    dependsOn(
        ":store6-swift-dumps-objc:checkSwiftDump",
        ":store6-swift-dumps-skie:checkSwiftDump",
        ":store6-swift-dumps-mutations-objc:checkSwiftDump",
        ":store6-swift-dumps-mutations-skie:checkSwiftDump",
    )
}

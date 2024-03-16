import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.js.npm.tasks.KotlinNpmInstallTask

plugins {
    id("org.jlleitschuh.gradle.ktlint") version "11.0.0"
    id("com.diffplug.spotless") version "6.4.1"
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
        classpath(libs.kover.plugin)
        classpath(libs.atomic.fu.gradle.plugin)
        classpath(libs.kmmBridge.gradle.plugin)
    }
}

allprojects {
    repositories {
        mavenCentral()
        google()
    }
}

subprojects {
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
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
        }
    }

    withType<JavaCompile>().configureEach {
        sourceCompatibility = JavaVersion.VERSION_17.name
        targetCompatibility = JavaVersion.VERSION_17.name
    }
}

// Workaround for https://youtrack.jetbrains.com/issue/KT-62040
tasks.getByName("wrapper")

// Workaround for https://youtrack.jetbrains.com/issue/KT-63014
plugins.withType<NodeJsRootPlugin> {
    extensions.configure(NodeJsRootExtension::class) {
        nodeVersion = "21.0.0-v8-canary20231019bd785be450"
        nodeDownloadBaseUrl = "https://nodejs.org/download/v8-canary"
    }
    tasks.withType<KotlinNpmInstallTask> {
        args.add("--ignore-engines")
    }
}

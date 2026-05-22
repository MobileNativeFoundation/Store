import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.ktlint)
    alias(libs.plugins.spotless)
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
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "com.diffplug.spotless")

    spotless {
        kotlin {
            target("src/**/*.kt")
        }
    }
}

tasks {
    withType<KotlinCompile> {
        compilerOptions {
            jvmTarget = JvmTarget.fromTarget(libs.versions.jvmCompat.get())
        }
    }

    withType<JavaCompile>().configureEach {
        sourceCompatibility = libs.versions.jvmCompat.get()
        targetCompatibility = libs.versions.jvmCompat.get()
    }
}

// Workaround for https://youtrack.jetbrains.com/issue/KT-62040
tasks.getByName("wrapper")

tasks.named<UpdateDaemonJvm>("updateDaemonJvm") {
    // JDK 17 is the minumun version supported by the org.gradle.toolchains.foojay-resolver-convention plugin
    languageVersion = JavaLanguageVersion.of(17)
    vendor.set(JvmVendorSpec.AZUL)
}

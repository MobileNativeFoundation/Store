plugins {
    id("org.jlleitschuh.gradle.ktlint") version "11.0.0"
    id("com.diffplug.spotless") version "6.4.1"
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.12.1"
}

buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:${Version.androidGradlePlugin}")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${Version.baseKotlin}")
        classpath("org.jetbrains.kotlin:kotlin-serialization:${Version.baseKotlin}")
        classpath("org.jetbrains.kotlinx:binary-compatibility-validator:${Version.binaryCompatibilityValidator}")
        classpath("org.jetbrains.dokka:dokka-gradle-plugin:${Version.dokkaGradlePlugin}")
        classpath("org.jlleitschuh.gradle:ktlint-gradle:${Version.ktlintGradle}")
        classpath("com.diffplug.spotless:spotless-plugin-gradle:${Version.spotlessPluginGradle}")
        classpath("org.jacoco:org.jacoco.core:${Version.jacocoGradlePlugin}")
        classpath("com.vanniktech:gradle-maven-publish-plugin:${Version.mavenPublishPlugin}")
        classpath("org.jetbrains.kotlinx:kover:${Version.kover}")
        classpath("com.squareup.anvil:gradle-plugin:${Version.anvilGradlePlugin}")
        classpath("com.squareup.sqldelight:gradle-plugin:${Version.sqlDelightGradlePlugin}")
        classpath("org.jetbrains.kotlinx:atomicfu-gradle-plugin:${Version.atomicFuGradlePlugin}")
    }
}

allprojects {
    repositories {
        mavenCentral()
        google()
        maven {
            url = uri("https://plugins.gradle.org/m2/")
        }
    }

    // Workaround to prevent Gradle from stealing focus from other apps during tests run/etc.
    // https://gist.github.com/artem-zinnatullin/4c250e04636e25797165
    tasks.all {
        when (this) {
            is JavaForkOptions -> {
                jvmArgs?.add("-Djava.awt.headless=true")
            }
        }
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

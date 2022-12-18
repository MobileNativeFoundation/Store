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
        classpath("com.android.tools.build:gradle:${Version.androidGradlePlugin}")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${Version.baseKotlin}")
        classpath("org.jetbrains.kotlin:kotlin-serialization:${Version.baseKotlin}")
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
            jvmTarget = "11"
        }
    }

    withType<JavaCompile>().configureEach {
        sourceCompatibility = JavaVersion.VERSION_11.name
        targetCompatibility = JavaVersion.VERSION_11.name
    }
}

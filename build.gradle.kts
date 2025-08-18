plugins {
    alias(libs.plugins.ktlint)
    id("com.diffplug.spotless") version "6.25.0"
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

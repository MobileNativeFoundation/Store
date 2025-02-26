plugins {
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
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
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    spotless {
        kotlin {
            ktfmt(libs.versions.ktfmt.get()).googleStyle()
            target("src/**/*.kt")
            trimTrailingWhitespace()
            endWithNewline()
        }

        kotlinGradle {
            ktfmt(libs.versions.ktfmt.get()).googleStyle()
            target("src/**/*.kts")
            trimTrailingWhitespace()
            endWithNewline()
        }
    }

    detekt {
        buildUponDefaultConfig = true
        config.setFrom("$rootDir/config/detekt/rules.yml")
        source.setFrom("src")
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

// Workaround for https://youtrack.jetbrains.com/issue/KT-62040
tasks.getByName("wrapper")

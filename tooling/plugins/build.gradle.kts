import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
    alias(libs.plugins.ktlint)
}

group = "org.mobilenativefoundation.store"

java {
    val compatVersion = JavaVersion.toVersion(libs.versions.jvmToolchain.get())
    sourceCompatibility = compatVersion
    targetCompatibility = compatVersion

    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.jvmToolchain.get()))
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(libs.versions.jvmToolchain.get())
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.dokka.gradle.plugin)
    compileOnly(libs.maven.publish.plugin)
    compileOnly(libs.kmmBridge.gradle.plugin)
    compileOnly(libs.atomic.fu.gradle.plugin)
    compileOnly(libs.ktlint.gradle.plugin)
    compileOnly(libs.spotless.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("kotlinMultiplatformConventionPlugin") {
            id = "org.mobilenativefoundation.store.multiplatform"
            implementationClass = "org.mobilenativefoundation.store.tooling.plugins.KotlinMultiplatformConventionPlugin"
        }

        register("androidConventionPlugin") {
            id = "org.mobilenativefoundation.store.android"
            implementationClass = "org.mobilenativefoundation.store.tooling.plugins.AndroidConventionPlugin"
        }

        register("formattingConventionPlugin") {
            id = "org.mobilenativefoundation.store.formatting"
            implementationClass = "org.mobilenativefoundation.store.tooling.plugins.FormattingConventionPlugin"
        }
    }
}

ktlint {
    version = libs.versions.ktlint.get()
    additionalEditorconfig.put("ktlint_standard_function-expression-body", "disabled")
    additionalEditorconfig.put("ktlint_standard_function-signature", "disabled")
    additionalEditorconfig.put("ktlint_standard_multiline-expression-wrapping", "disabled")
    additionalEditorconfig.put("ktlint_standard_string-template-indent", "disabled")
}

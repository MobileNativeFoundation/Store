import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.kotlin.multiplatform) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
    alias(libs.plugins.atomicfu) apply false
    alias(libs.plugins.kotlin.cocoapods) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.spotless)
    alias(libs.plugins.binary.compatibility.validator) apply false
    alias(libs.plugins.kmmbridge.github) apply false
}

val ktLintVersion = libs.versions.ktlint.get()
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "com.diffplug.spotless")

    spotless {
        kotlin {
            target("src/**/*.kt")
        }
    }
    ktlint {
        version = ktLintVersion
        additionalEditorconfig.put("ktlint_standard_function-expression-body", "disabled")
        additionalEditorconfig.put("ktlint_standard_class-signature", "disabled")
        additionalEditorconfig.put("ktlint_standard_spacing-between-declarations-with-comments", "disabled")
        additionalEditorconfig.put("ktlint_standard_when-entry-bracing", "disabled")
        additionalEditorconfig.put("ktlint_standard_blank-line-between-when-conditions", "disabled")
        additionalEditorconfig.put("ktlint_standard_kdoc", "disabled")
        additionalEditorconfig.put("ktlint_standard_max-line-length", "disabled")
        additionalEditorconfig.put("ktlint_standard_chain-method-continuation", "disabled")
        additionalEditorconfig.put("ktlint_standard_function-signature", "disabled")
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
    // JDK 17 is the minimum version supported by the org.gradle.toolchains.foojay-resolver-convention plugin
    languageVersion = JavaLanguageVersion.of(17)
    vendor.set(JvmVendorSpec.AZUL)
}

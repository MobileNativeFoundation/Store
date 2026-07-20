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

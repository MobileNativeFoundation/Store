package org.mobilenativefoundation.store.tooling.plugins

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension

val Project.versionCatalog: VersionCatalog
    get() = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

val VersionCatalog.jvmToolchainVersion: String
    get() = findVersion("jvmToolchain").get().requiredVersion

val VersionCatalog.jvmCompatVersion: String
    get() = findVersion("jvmCompat").get().requiredVersion

val VersionCatalog.store: String
    get() = findVersion("store").get().requiredVersion

val VersionCatalog.androidCompileSdk: String
    get() = findVersion("androidCompileSdk").get().requiredVersion

val VersionCatalog.androidMinSdk: String
    get() = findVersion("androidMinSdk").get().requiredVersion

val VersionCatalog.androidTargetSdk: String
    get() = findVersion("androidTargetSdk").get().requiredVersion

package org.mobilenativefoundation.store.tooling.plugins

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Subset variant: identical store6 conventions, zero targets — the module's kotlin {}
 * block declares a subset of the canonical 12 (androidTarget() required, spellings must
 * match the full plugin). First consumer: store6-room (Room 2.8.x has no js/wasmJs/mingwX64).
 */
class Store6MultiplatformSubsetConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.configureStore6Module()
    }
}

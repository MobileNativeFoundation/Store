package org.mobilenativefoundation.store.tooling.plugins

import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.assign
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jlleitschuh.gradle.ktlint.KtlintExtension

class FormattingConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("org.jlleitschuh.gradle.ktlint")
        target.pluginManager.apply("com.diffplug.spotless")
        target.extensions.configure<SpotlessExtension> {
            kotlin {
                target("src/**/*.kt")
            }
        }
        val versionCatalog = target.extensions.getByType<VersionCatalogsExtension>().named("libs")
        target.extensions.configure<KtlintExtension> {
            version = versionCatalog.findVersion("ktlint").get().requiredVersion
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
}

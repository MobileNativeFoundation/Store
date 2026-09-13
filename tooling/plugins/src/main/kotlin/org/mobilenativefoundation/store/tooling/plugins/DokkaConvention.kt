package org.mobilenativefoundation.store.tooling.plugins

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.dokka.gradle.DokkaExtension

fun Project.configureKotlin() {
    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(11))
        }
    }
}

fun Project.configureMavenPublishing() =
    extensions.configure<MavenPublishBaseExtension> {
        publishToMavenCentral(automaticRelease = true)
        signAllPublications()
    }

fun Project.configureDokka() {
    val moduleDocumentation = layout.projectDirectory.file("dokka/Module.md")

    extensions.configure<DokkaExtension> {
        dokkaPublications.named("html") {
            outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
        }
        dokkaSourceSets.configureEach {
            reportUndocumented.set(false)
            skipDeprecated.set(true)
            jdkVersion.set(11)
            externalDocumentationLinks.register("kotlinx-coroutines") {
                url("https://kotlinlang.org/api/kotlinx.coroutines/")
                packageListUrl("https://kotlinlang.org/api/kotlinx.coroutines/package-list")
            }
            if (project.name == "mutations") {
                externalDocumentationLinks.register("core") {
                    url("https://store.mobilenativefoundation.org/reference/core/")
                    packageListUrl.set(
                        rootProject
                            .project(":core")
                            .layout.buildDirectory
                            .file("dokka/html/core/package-list")
                            .map { it.asFile.toURI() },
                    )
                }
            }
        }

        if (moduleDocumentation.asFile.isFile) {
            dokkaSourceSets.matching { it.name == "commonMain" }.configureEach {
                includes.from(moduleDocumentation)
            }
        }
    }

    if (project.name == "mutations") {
        tasks.named("dokkaGeneratePublicationHtml").configure {
            dependsOn(":core:dokkaGeneratePublicationHtml")
        }
    }
    tasks.register("dokkaHtml") {
        group = "documentation"
        description = "Generates Dokka HTML documentation."
        dependsOn("dokkaGeneratePublicationHtml")
    }
}

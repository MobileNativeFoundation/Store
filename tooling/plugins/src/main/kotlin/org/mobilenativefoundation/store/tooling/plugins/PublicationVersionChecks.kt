package org.mobilenativefoundation.store.tooling.plugins

import groovy.json.JsonSlurper
import org.gradle.api.Project
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.kotlin.dsl.withType
import org.w3c.dom.Element
import java.io.File
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

fun Project.configurePublicationVersionChecks() {
    val expectedVersion = versionCatalog.store
    val expectedGroup = providers.gradleProperty("GROUP").get()
    val pomTasks = tasks.withType<GenerateMavenPom>()
    val metadataTasks = tasks.withType<GenerateModuleMetadata>()

    pomTasks.configureEach {
        inputs.property("storePublicationVersion", expectedVersion)
        inputs.property("storePublicationGroup", expectedGroup)
        doLast {
            verifyPomVersions((this as GenerateMavenPom).destination, expectedGroup, expectedVersion)
        }
    }
    metadataTasks.configureEach {
        inputs.property("storePublicationVersion", expectedVersion)
        inputs.property("storePublicationGroup", expectedGroup)
        doLast {
            verifyModuleVersions((this as GenerateModuleMetadata).outputFile.get().asFile, expectedGroup, expectedVersion)
        }
    }

    val verification = tasks.register("verifyPublicationVersions") {
        group = "verification"
        description = "Generates and checks publication versions against the Store version catalog."
        dependsOn(pomTasks, metadataTasks)
    }
    tasks.matching { it.name == "check" }.configureEach { dependsOn(verification) }
}

internal fun verifyPomVersions(file: File, expectedGroup: String, expectedVersion: String) {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }
    val project = factory.newDocumentBuilder().parse(file).documentElement
    require(project.childText("groupId") == expectedGroup) { "${file.path}: unexpected publication group" }
    requireVersion(project.childText("version"), expectedVersion, "${file.path}: project")

    for (tag in listOf("dependency", "parent")) {
        val references = project.getElementsByTagNameNS("*", tag)
        for (index in 0 until references.length) {
            val reference = references.item(index) as Element
            if (reference.childText("groupId") == expectedGroup) {
                requireVersion(reference.childText("version"), expectedVersion, "${file.path}: $tag ${reference.childText("artifactId")}")
            }
        }
    }
}

internal fun verifyModuleVersions(file: File, expectedGroup: String, expectedVersion: String) {
    val metadata = JsonSlurper().parse(file) as Map<*, *>
    val component = metadata["component"] as Map<*, *>
    require(component["group"] == expectedGroup) { "${file.path}: unexpected component group" }
    requireVersion(component["version"], expectedVersion, "${file.path}: component")
    component["url"]?.let { verifyRedirect(it as String, component["module"] as String, expectedVersion, file) }

    for (variant in metadata["variants"] as List<*>) {
        variant as Map<*, *>
        val location = "${file.path}: variant ${variant["name"]}"
        for (kind in listOf("dependencies", "dependencyConstraints")) {
            for (reference in variant[kind] as? List<*> ?: emptyList<Any>()) {
                reference as Map<*, *>
                if (reference["group"] == expectedGroup) {
                    val version = reference["version"] as? Map<*, *> ?: emptyMap<Any, Any>()
                    val selectors = listOf("requires", "strictly", "prefers").mapNotNull { version[it] }
                    require(selectors.isNotEmpty()) { "$location: missing version for ${reference["module"]}" }
                    selectors.forEach { requireVersion(it, expectedVersion, "$location: ${reference["module"]}") }
                    require(expectedVersion !in (version["rejects"] as? List<*> ?: emptyList<Any>())) {
                        "$location: ${reference["module"]} rejects $expectedVersion"
                    }
                }
            }
        }
        val redirect = variant["available-at"] as? Map<*, *>
        if (redirect != null && redirect["group"] == expectedGroup) {
            requireVersion(redirect["version"], expectedVersion, "$location: available-at")
            verifyRedirect(redirect["url"] as String, redirect["module"] as String, expectedVersion, file)
        }
    }
}

private fun Element.childText(name: String): String? =
    (0 until childNodes.length)
        .map { childNodes.item(it) }
        .filterIsInstance<Element>()
        .firstOrNull { it.localName == name }
        ?.textContent
        ?.trim()

private fun requireVersion(actual: Any?, expected: String, location: String) {
    require(actual == expected) { "$location has version '$actual'; expected '$expected' from the Store version catalog" }
}

private fun verifyRedirect(url: String, module: String, expectedVersion: String, file: File) {
    val path = URI(url).normalize().path.orEmpty()
    val target = "$module/$expectedVersion/$module-$expectedVersion.module"
    require(path == target || path.endsWith("/$target")) {
        "${file.path}: metadata redirect '$url' does not reference '$target'"
    }
}

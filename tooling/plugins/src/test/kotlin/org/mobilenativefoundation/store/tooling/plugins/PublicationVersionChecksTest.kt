package org.mobilenativefoundation.store.tooling.plugins

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PublicationVersionChecksTest {
    private val group = "org.mobilenativefoundation.store"
    private val version = "5.1.0-beta01"

    @Test
    fun validPomPreservesExternalDependencyVersions() {
        withFile(pom(version, version)) { verifyPomVersions(it, group, version) }
    }

    @Test
    fun rx2UnspecifiedVersionIsRejected() {
        withFile(pom("unspecified", version)) {
            val error = assertFailsWith<IllegalArgumentException> { verifyPomVersions(it, group, version) }
            assertTrue(error.message.orEmpty().contains("project has version 'unspecified'"))
        }
    }

    @Test
    fun staleInternalPomDependencyIsRejected() {
        withFile(pom(version, "5.1.0-alpha12")) {
            val error = assertFailsWith<IllegalArgumentException> { verifyPomVersions(it, group, version) }
            assertTrue(error.message.orEmpty().contains("dependency store5"))
        }
    }

    @Test
    fun validModuleIncludesRootComponentRedirect() {
        withFile(module()) { verifyModuleVersions(it, group, version) }
    }

    @Test
    fun unspecifiedModuleComponentIsRejected() {
        withFile(module(componentVersion = "unspecified")) {
            assertFailsWith<IllegalArgumentException> { verifyModuleVersions(it, group, version) }
        }
    }

    @Test
    fun staleInternalModuleDependencyIsRejected() {
        withFile(module(dependencyVersion = "5.1.0-alpha12")) {
            val error = assertFailsWith<IllegalArgumentException> { verifyModuleVersions(it, group, version) }
            assertTrue(error.message.orEmpty().contains("store5"))
        }
    }

    @Test
    fun staleTargetReferenceIsRejected() {
        withFile(module(targetVersion = "5.1.0-alpha12")) {
            assertFailsWith<IllegalArgumentException> { verifyModuleVersions(it, group, version) }
        }
    }

    @Test
    fun staleTargetUrlIsRejectedEvenWhenVersionMatches() {
        withFile(module(targetUrlVersion = "5.1.0-alpha12")) {
            assertFailsWith<IllegalArgumentException> { verifyModuleVersions(it, group, version) }
        }
    }

    @Test
    fun staleTargetFilenameIsRejectedEvenWhenDirectoryMatches() {
        withFile(module(targetFileVersion = "5.1.0-alpha12")) {
            assertFailsWith<IllegalArgumentException> { verifyModuleVersions(it, group, version) }
        }
    }

    @Test
    fun staleRootFilenameIsRejectedEvenWhenDirectoryMatches() {
        withFile(module(rootFileVersion = "5.1.0-alpha12")) {
            assertFailsWith<IllegalArgumentException> { verifyModuleVersions(it, group, version) }
        }
    }

    private fun pom(ownVersion: String, dependencyVersion: String) =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>$group</groupId><artifactId>rx2</artifactId><version>$ownVersion</version>
          <dependencies>
            <dependency><groupId>$group</groupId><artifactId>store5</artifactId><version>$dependencyVersion</version></dependency>
            <dependency><groupId>io.reactivex.rxjava2</groupId><artifactId>rxjava</artifactId><version>2.2.21</version></dependency>
          </dependencies>
        </project>
        """.trimIndent()

    private fun module(
        componentVersion: String = version,
        dependencyVersion: String = version,
        targetVersion: String = version,
        targetUrlVersion: String = version,
        targetFileVersion: String = targetUrlVersion,
        rootFileVersion: String = version,
    ) =
        """
        {
          "formatVersion": "1.1",
          "component": {"group": "$group", "module": "store5", "version": "$componentVersion", "url": "../../store5/$version/store5-$rootFileVersion.module"},
          "variants": [
            {"name": "api", "dependencies": [
              {"group": "$group", "module": "store5", "version": {"requires": "$dependencyVersion"}},
              {"group": "org.jetbrains.kotlin", "module": "kotlin-stdlib", "version": {"requires": "2.3.21"}}
            ]},
            {"name": "iosArm64Api", "available-at": {
              "group": "$group", "module": "store5-iosarm64", "version": "$targetVersion",
              "url": "../../store5-iosarm64/$targetUrlVersion/store5-iosarm64-$targetFileVersion.module"
            }}
          ]
        }
        """.trimIndent()

    private fun withFile(content: String, check: (File) -> Unit) {
        val directory = createTempDirectory("store-publication-test").toFile()
        try {
            val file = File(directory, "publication").apply { writeText(content) }
            check(file)
        } finally {
            directory.deleteRecursively()
        }
    }
}

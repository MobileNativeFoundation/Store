// DRAFT — new file at
// tooling/plugins/src/test/kotlin/org/mobilenativefoundation/store/tooling/plugins/SwiftDumpTasksPluginTest.kt
//
// Mechanism: ProjectBuilder (org.gradle.testfixtures), NOT GradleRunner. Rationale:
//   - T4.9 requires the reds to "inspect configured task inputs/outputs". ProjectBuilder gives
//     direct typed access to task properties (linkedHeader, outputHeaderName, committedDirectory,
//     failureMessage, description) without linking any iOS framework. GradleRunner is black-box:
//     it could only observe task EXECUTION, which requires a Kotlin/Native toolchain and a real
//     linked framework per test — unusable as a fast plugin red.
//   - ProjectBuilder ships inside gradleApi(), which the `kotlin-dsl` plugin already puts on the
//     compile classpath. gradleTestKit() is still added per the plan so the lane can grow
//     functional (GradleRunner) coverage later without another build change.
//   - String dependsOn("linkDebugFrameworkIosArm64") is only resolved at task-graph time, so
//     realizing the dump tasks via getByName in a ProjectBuilder project is safe with no KMP
//     plugin applied.
//
// FIRST-RED STATUS (against the plugin at e3c9e6d): this file does not compile —
// `SwiftDumpExtension` does not exist and the plugins expose no configuration door. The archived
// first-red evidence is therefore the compiler transcript of
//   ./gradlew -p tooling :plugins:test --tests '...SwiftDumpTasksPluginTest' --stacktrace
// (T4.9 explicitly allows "XML/compiler transcript"). All three named reds share that transcript;
// coreDefaults_remainByteEquivalent then stays as the permanent byte-equivalence pin once green.
//
// Required test dependencies (see tooling-plugins-build.gradle.kts.patch):
//   testImplementation(kotlin("test", embeddedKotlinVersion))
//   testImplementation(libs.junit)          // JUnit 4 runner for kotlin-test's default JVM variant
//   testImplementation(gradleTestKit())

package org.mobilenativefoundation.store.tooling.plugins

import org.gradle.api.Project
import org.gradle.api.tasks.Sync
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SwiftDumpTasksPluginTest {

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun rootProject(): Project = ProjectBuilder.builder().withName("Store5").build()

    private fun childProject(root: Project, name: String): Project =
        ProjectBuilder.builder().withName(name).withParent(root).build()

    private fun Project.expectedLinkedHeader(frameworkName: String): File =
        layout.buildDirectory
            .file("bin/iosArm64/debugFramework/$frameworkName.framework/Headers/$frameworkName.h")
            .get()
            .asFile

    private fun Project.expectedStagedDirectory(): File =
        layout.buildDirectory.dir("swift-dump").get().asFile

    private fun Project.expectedCommittedDirectory(relativePath: String): File =
        rootProject.layout.projectDirectory.dir(relativePath).asFile

    // ---------------------------------------------------------------------------------------------
    // Red 1 — ObjC plugin honors per-project configuration
    // ---------------------------------------------------------------------------------------------

    @Test
    fun objcProject_usesConfiguredFrameworkOutputAndCommittedDirectory() {
        val root = rootProject()
        val project = childProject(root, "store6-swift-dumps-mutations-objc")
        project.plugins.apply(Store6ObjcSwiftDumpPlugin::class.java)
        project.extensions.getByType(SwiftDumpExtension::class.java).apply {
            surfaceName.set("store6-mutations")
            frameworkName.set("Store6Mutations")
            outputHeaderName.set("Store6Mutations.h")
            committedDumpPath.set("store6-mutations/api/swift/objc")
        }

        val generate = project.tasks.getByName("generateSwiftDump") as GenerateObjcSwiftDumpTask
        assertEquals("Store6 verification", generate.group)
        assertEquals(
            "Generates the sanitized Obj-C export dump for store6-mutations.",
            generate.description,
        )
        assertEquals(
            project.expectedLinkedHeader("Store6Mutations"),
            generate.linkedHeader.get().asFile,
        )
        assertEquals("Store6Mutations.h", generate.outputHeaderName.get())
        assertEquals(project.expectedStagedDirectory(), generate.outputDirectory.get().asFile)

        val refresh = project.tasks.getByName("refreshSwiftDump") as Sync
        assertEquals(
            "Refreshes the committed Obj-C export dump for store6-mutations.",
            refresh.description,
        )
        assertEquals(
            project.expectedCommittedDirectory("store6-mutations/api/swift/objc"),
            refresh.destinationDir,
        )

        val check = project.tasks.getByName("checkSwiftDump") as CheckSwiftDumpTask
        assertEquals(
            "Checks the committed Obj-C export dump for store6-mutations.",
            check.description,
        )
        assertEquals(project.expectedStagedDirectory(), check.generatedDirectory.get().asFile)
        assertEquals(
            project.expectedCommittedDirectory("store6-mutations/api/swift/objc"),
            check.committedDirectory.get().asFile,
        )
        assertEquals(
            "Obj-C export dump for store6-mutations has drifted from " +
                "store6-mutations/api/swift/objc. " +
                "Run ./gradlew refreshSwiftDumps and commit the result.",
            check.failureMessage.get(),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Red 2 — SKIE plugin honors per-project configuration (header AND combined-Swift outputs)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun skieProject_usesConfiguredFrameworkOutputsAndCommittedDirectory() {
        val root = rootProject()
        val project = childProject(root, "store6-swift-dumps-mutations-skie")
        project.plugins.apply(Store6SkieSwiftDumpPlugin::class.java)
        project.extensions.getByType(SwiftDumpExtension::class.java).apply {
            surfaceName.set("store6-mutations")
            frameworkName.set("Store6MutationsSkie")
            outputHeaderName.set("Store6MutationsSkie.h")
            outputSwiftName.set("Store6MutationsSkie.swift")
            committedDumpPath.set("store6-mutations/api/swift/skie")
        }

        val validate = project.tasks.getByName("validateSkieSwiftLayout") as ValidateSkieSwiftLayoutTask
        assertEquals(
            "Validates the pinned SKIE-generated Swift layout for store6-mutations.",
            validate.description,
        )
        assertEquals(
            "skie/binaries/debugFramework/DEBUG/iosArm64/swift/generated",
            validate.supportedLayout.get(),
        )

        val generate = project.tasks.getByName("generateSwiftDump") as GenerateSkieSwiftDumpTask
        assertEquals("Store6 verification", generate.group)
        assertEquals(
            "Generates the sanitized SKIE dump for store6-mutations.",
            generate.description,
        )
        assertEquals(
            project.expectedLinkedHeader("Store6MutationsSkie"),
            generate.linkedHeader.get().asFile,
        )
        assertEquals("Store6MutationsSkie.h", generate.outputHeaderName.get())
        assertEquals("Store6MutationsSkie.swift", generate.outputSwiftName.get())
        assertEquals(project.expectedStagedDirectory(), generate.outputDirectory.get().asFile)

        val refresh = project.tasks.getByName("refreshSwiftDump") as Sync
        assertEquals(
            "Refreshes the committed SKIE dump for store6-mutations.",
            refresh.description,
        )
        assertEquals(
            project.expectedCommittedDirectory("store6-mutations/api/swift/skie"),
            refresh.destinationDir,
        )

        val check = project.tasks.getByName("checkSwiftDump") as CheckSwiftDumpTask
        assertEquals(
            "Checks the committed SKIE dump for store6-mutations.",
            check.description,
        )
        assertEquals(
            project.expectedCommittedDirectory("store6-mutations/api/swift/skie"),
            check.committedDirectory.get().asFile,
        )
        assertEquals(
            "SKIE dump for store6-mutations has drifted from " +
                "store6-mutations/api/swift/skie. " +
                "Run ./gradlew refreshSwiftDumps and commit the result.",
            check.failureMessage.get(),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Red 3 — unconfigured (core) projects keep EXACT pre-parameterization wiring
    // Every literal below is transcribed from SwiftDumpTasksPlugin.kt at e3c9e6d; do not "sync"
    // these with the plugin source when editing the plugin — they are the byte-equivalence pin.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun coreDefaults_remainByteEquivalent() {
        val root = rootProject()

        // --- ObjC lane, no configuration (mirrors store6-swift-dumps/objc) ---
        val objc = childProject(root, "store6-swift-dumps-objc")
        objc.plugins.apply(Store6ObjcSwiftDumpPlugin::class.java)

        val objcGenerate = objc.tasks.getByName("generateSwiftDump") as GenerateObjcSwiftDumpTask
        assertEquals("Store6 verification", objcGenerate.group)
        assertEquals(
            "Generates the sanitized Obj-C export dump for store6-core.",
            objcGenerate.description,
        )
        assertEquals(objc.expectedLinkedHeader("Store6Core"), objcGenerate.linkedHeader.get().asFile)
        assertEquals("Store6Core.h", objcGenerate.outputHeaderName.get())
        assertEquals(objc.expectedStagedDirectory(), objcGenerate.outputDirectory.get().asFile)

        val objcRefresh = objc.tasks.getByName("refreshSwiftDump") as Sync
        assertEquals("Store6 verification", objcRefresh.group)
        assertEquals(
            "Refreshes the committed Obj-C export dump for store6-core.",
            objcRefresh.description,
        )
        assertEquals(
            objc.expectedCommittedDirectory("store6-core/api/swift/objc"),
            objcRefresh.destinationDir,
        )

        val objcCheck = objc.tasks.getByName("checkSwiftDump") as CheckSwiftDumpTask
        assertEquals("Store6 verification", objcCheck.group)
        assertEquals(
            "Checks the committed Obj-C export dump for store6-core.",
            objcCheck.description,
        )
        assertEquals(objc.expectedStagedDirectory(), objcCheck.generatedDirectory.get().asFile)
        assertEquals(
            objc.expectedCommittedDirectory("store6-core/api/swift/objc"),
            objcCheck.committedDirectory.get().asFile,
        )
        assertEquals(
            "Obj-C export dump for store6-core has drifted from store6-core/api/swift/objc. " +
                "Run ./gradlew refreshSwiftDumps and commit the result.",
            objcCheck.failureMessage.get(),
        )

        // --- SKIE lane, no configuration (mirrors store6-swift-dumps/skie) ---
        val skie = childProject(root, "store6-swift-dumps-skie")
        skie.plugins.apply(Store6SkieSwiftDumpPlugin::class.java)

        val skieValidate = skie.tasks.getByName("validateSkieSwiftLayout") as ValidateSkieSwiftLayoutTask
        assertEquals("Store6 verification", skieValidate.group)
        assertEquals(
            "Validates the pinned SKIE-generated Swift layout for store6-core.",
            skieValidate.description,
        )
        assertEquals(
            "skie/binaries/debugFramework/DEBUG/iosArm64/swift/generated",
            skieValidate.supportedLayout.get(),
        )
        assertEquals(
            skie.layout.buildDirectory
                .dir("skie/binaries/debugFramework/DEBUG/iosArm64/swift/generated")
                .get()
                .asFile,
            skieValidate.generatedSwiftRoot.get().asFile,
        )

        val skieGenerate = skie.tasks.getByName("generateSwiftDump") as GenerateSkieSwiftDumpTask
        assertEquals("Store6 verification", skieGenerate.group)
        assertEquals(
            "Generates the sanitized SKIE dump for store6-core.",
            skieGenerate.description,
        )
        assertEquals(
            skie.expectedLinkedHeader("Store6CoreSkie"),
            skieGenerate.linkedHeader.get().asFile,
        )
        assertEquals("Store6CoreSkie.h", skieGenerate.outputHeaderName.get())
        assertEquals("Store6CoreSkie.swift", skieGenerate.outputSwiftName.get())
        assertEquals(
            "skie/binaries/debugFramework/DEBUG/iosArm64/swift/generated",
            skieGenerate.supportedLayout.get(),
        )
        assertEquals(skie.expectedStagedDirectory(), skieGenerate.outputDirectory.get().asFile)

        val skieRefresh = skie.tasks.getByName("refreshSwiftDump") as Sync
        assertEquals("Store6 verification", skieRefresh.group)
        assertEquals(
            "Refreshes the committed SKIE dump for store6-core.",
            skieRefresh.description,
        )
        assertEquals(
            skie.expectedCommittedDirectory("store6-core/api/swift/skie"),
            skieRefresh.destinationDir,
        )

        val skieCheck = skie.tasks.getByName("checkSwiftDump") as CheckSwiftDumpTask
        assertEquals("Store6 verification", skieCheck.group)
        assertEquals(
            "Checks the committed SKIE dump for store6-core.",
            skieCheck.description,
        )
        assertEquals(skie.expectedStagedDirectory(), skieCheck.generatedDirectory.get().asFile)
        assertEquals(
            skie.expectedCommittedDirectory("store6-core/api/swift/skie"),
            skieCheck.committedDirectory.get().asFile,
        )
        assertEquals(
            "SKIE dump for store6-core has drifted from store6-core/api/swift/skie. " +
                "Run ./gradlew refreshSwiftDumps and commit the result.",
            skieCheck.failureMessage.get(),
        )
    }
}

// DRAFT — full replacement for
// tooling/plugins/src/main/kotlin/org/mobilenativefoundation/store/tooling/plugins/SwiftDumpTasksPlugin.kt
//
// Config mechanism: a project extension (`store6SwiftDump`) with core-valued conventions.
// Why an extension and not gradle properties:
//   1. The existing plugins are convention plugins configured per applying project; the surface
//      identity (framework name, committed directory) is per-project build topology, not an
//      invocation-time switch. Gradle properties (-P / gradle.properties) are global to the build
//      invocation and would let one lane's value leak into another; an extension is scoped to
//      exactly one applying project.
//   2. Property-based config cannot be expressed in the applying build.gradle.kts next to the
//      framework declaration it must stay in sync with; the extension keeps
//      `baseName = "Store6Mutations"` and `frameworkName.set("Store6Mutations")` in one file
//      under one review.
//   3. Conventions (`convention(...)`) give the byte-equivalence guarantee mechanically: an
//      unconfigured applying project (the two existing core lanes) resolves to exactly the
//      previous hardcoded strings, so the core build files need zero edits and
//      `coreDefaults_remainByteEquivalent` pins that.
// Task names are UNCHANGED for every applying project: generateSwiftDump, refreshSwiftDump,
// checkSwiftDump, validateSkieSwiftLayout. Group is UNCHANGED: "Store6 verification".
// All previously hardcoded strings are now derived from the extension; with defaults the derived
// values are byte-identical to the strings at e3c9e6d (see the hardcode inventory in NOTES.md).

package org.mobilenativefoundation.store.tooling.plugins

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

private const val SKIE_GENERATED_SWIFT_LAYOUT =
    "skie/binaries/debugFramework/DEBUG/iosArm64/swift/generated"

private const val SWIFT_DUMP_EXTENSION_NAME = "store6SwiftDump"

private const val VERIFICATION_GROUP = "Store6 verification"

private val volatileVersionLine =
    Regex("""(?i)^\s*(//|/\*|\*)?\s*(compiler|kotlin|skie)\s+version\b.*""")

/**
 * Per-project configuration for the Swift dump lanes.
 *
 * Every property has a core-valued convention so the two existing core lanes
 * (store6-swift-dumps/objc and store6-swift-dumps/skie) apply the plugins without configuration
 * and keep byte-identical task wiring. New lanes (store6-swift-dumps/mutations-objc and
 * mutations-skie) override all identity properties explicitly.
 */
abstract class SwiftDumpExtension {
    /** Reviewed surface module name, e.g. "store6-core" or "store6-mutations". */
    abstract val surfaceName: Property<String>

    /** Framework base name, e.g. "Store6Core", "Store6CoreSkie", "Store6Mutations". */
    abstract val frameworkName: Property<String>

    /** Staged header file name. Convention: "<frameworkName>.h". */
    abstract val outputHeaderName: Property<String>

    /** Staged combined Swift file name (SKIE lanes only). Convention: "<frameworkName>.swift". */
    abstract val outputSwiftName: Property<String>

    /**
     * Committed dump directory, relative to the root project,
     * e.g. "store6-core/api/swift/objc". Convention: "<surfaceName>/api/swift/<objc|skie>".
     */
    abstract val committedDumpPath: Property<String>
}

@CacheableTask
abstract class GenerateObjcSwiftDumpTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val linkedHeader: RegularFileProperty

    @get:Input
    abstract val outputHeaderName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val header = linkedHeader.get().asFile
        if (!header.isFile) {
            throw GradleException("Expected Obj-C export header at ${header.path}")
        }

        val output = outputDirectory.get().asFile
        recreateDirectory(output)
        output.resolve(outputHeaderName.get()).writeText(sanitized(header.readText()))
    }
}

@DisableCachingByDefault(
    because = "SKIE does not expose the generated Swift directory as a declared task output",
)
abstract class GenerateSkieSwiftDumpTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val linkedHeader: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generatedSwiftRoot: DirectoryProperty

    @get:Input
    abstract val supportedLayout: Property<String>

    @get:Input
    abstract val outputHeaderName: Property<String>

    @get:Input
    abstract val outputSwiftName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val header = linkedHeader.get().asFile
        if (!header.isFile) {
            throw GradleException("Expected SKIE-processed header at ${header.path}")
        }

        val swiftRoot = generatedSwiftRoot.get().asFile
        if (!swiftRoot.isDirectory) {
            throw unsupportedSkieLayout(swiftRoot, supportedLayout.get())
        }

        val swiftFiles = swiftRoot.walkTopDown()
            .filter { it.isFile && it.extension == "swift" }
            .sortedBy { it.relativeTo(swiftRoot).invariantSeparatorsPath() }
            .toList()
        if (swiftFiles.isEmpty()) {
            throw unsupportedSkieLayout(swiftRoot, supportedLayout.get())
        }

        val output = outputDirectory.get().asFile
        recreateDirectory(output)
        output.resolve(outputHeaderName.get()).writeText(sanitized(header.readText()))
        val combinedSwift =
            buildString {
                swiftFiles.forEach { file ->
                    val relativePath = file.relativeTo(swiftRoot).invariantSeparatorsPath()
                    appendLine("// FILE: $relativePath")
                    append(sanitized(file.readText()))
                    appendLine()
                }
            }.trimEnd() + "\n"
        output.resolve(outputSwiftName.get()).writeText(combinedSwift)
    }
}

@DisableCachingByDefault(because = "Layout validation must run after every SKIE framework link")
abstract class ValidateSkieSwiftLayoutTask : DefaultTask() {
    @get:Internal
    abstract val generatedSwiftRoot: DirectoryProperty

    @get:Input
    abstract val supportedLayout: Property<String>

    @TaskAction
    fun validate() {
        val swiftRoot = generatedSwiftRoot.get().asFile
        val containsSwiftSource = swiftRoot.isDirectory && swiftRoot.walkTopDown().any {
            it.isFile && it.extension == "swift"
        }
        if (!containsSwiftSource) {
            throw unsupportedSkieLayout(swiftRoot, supportedLayout.get())
        }
    }
}

@DisableCachingByDefault(because = "Verification task has no outputs")
abstract class CheckSwiftDumpTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generatedFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val committedFiles: ConfigurableFileCollection

    @get:Internal
    abstract val generatedDirectory: DirectoryProperty

    @get:Internal
    abstract val committedDirectory: DirectoryProperty

    @get:Input
    abstract val failureMessage: Property<String>

    @TaskAction
    fun check() {
        val generated = snapshot(generatedDirectory.get().asFile)
        val committed = snapshot(committedDirectory.get().asFile)
        if (generated.isEmpty()) {
            throw GradleException("No generated Swift dump files are available to check.")
        }

        val missing = (generated.keys - committed.keys).sorted()
        val stale = (committed.keys - generated.keys).sorted()
        val changed = (generated.keys intersect committed.keys)
            .filterNot { generated.getValue(it).contentEquals(committed.getValue(it)) }
            .sorted()

        if (missing.isNotEmpty() || stale.isNotEmpty() || changed.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine(failureMessage.get())
                    appendDifference("Missing committed files", missing)
                    appendDifference("Stale committed files", stale)
                    appendDifference("Changed files", changed)
                }.trimEnd(),
            )
        }
    }
}

class Store6ObjcSwiftDumpPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            val dump = extensions.create(SWIFT_DUMP_EXTENSION_NAME, SwiftDumpExtension::class.java)
            dump.surfaceName.convention("store6-core")
            dump.frameworkName.convention("Store6Core")
            dump.outputHeaderName.convention(dump.frameworkName.map { "$it.h" })
            dump.outputSwiftName.convention(dump.frameworkName.map { "$it.swift" })
            dump.committedDumpPath.convention(dump.surfaceName.map { "$it/api/swift/objc" })

            val stagedDirectory = layout.buildDirectory.dir("swift-dump")
            val committedDumpDirectory = dump.committedDumpPath.map { path ->
                rootProject.layout.projectDirectory.dir(path)
            }
            val generate = tasks.register("generateSwiftDump", GenerateObjcSwiftDumpTask::class.java) {
                group = VERIFICATION_GROUP
                description = "Generates the sanitized Obj-C export dump for ${dump.surfaceName.get()}."
                dependsOn("linkDebugFrameworkIosArm64")
                linkedHeader.set(
                    dump.frameworkName.flatMap { framework ->
                        layout.buildDirectory.file(
                            "bin/iosArm64/debugFramework/${framework}.framework/Headers/${framework}.h",
                        )
                    },
                )
                outputHeaderName.set(dump.outputHeaderName)
                outputDirectory.set(stagedDirectory)
            }

            val refresh = tasks.register("refreshSwiftDump", Sync::class.java) {
                group = VERIFICATION_GROUP
                description = "Refreshes the committed Obj-C export dump for ${dump.surfaceName.get()}."
                dependsOn(generate)
                doNotTrackState("Refresh must always reconcile stale files in the committed dump.")
                from(stagedDirectory)
                into(committedDumpDirectory)
                includeEmptyDirs = false
            }

            tasks.register("checkSwiftDump", CheckSwiftDumpTask::class.java) {
                group = VERIFICATION_GROUP
                description = "Checks the committed Obj-C export dump for ${dump.surfaceName.get()}."
                dependsOn(generate)
                mustRunAfter(refresh)
                generatedFiles.from(stagedDirectory)
                committedFiles.from(committedDumpDirectory)
                generatedDirectory.set(stagedDirectory)
                committedDirectory.set(committedDumpDirectory)
                failureMessage.set(
                    dump.surfaceName.zip(dump.committedDumpPath) { surface, committedPath ->
                        "Obj-C export dump for $surface has drifted from $committedPath. " +
                            "Run ./gradlew refreshSwiftDumps and commit the result."
                    },
                )
            }
        }
    }
}

class Store6SkieSwiftDumpPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            val dump = extensions.create(SWIFT_DUMP_EXTENSION_NAME, SwiftDumpExtension::class.java)
            dump.surfaceName.convention("store6-core")
            dump.frameworkName.convention("Store6CoreSkie")
            dump.outputHeaderName.convention(dump.frameworkName.map { "$it.h" })
            dump.outputSwiftName.convention(dump.frameworkName.map { "$it.swift" })
            dump.committedDumpPath.convention(dump.surfaceName.map { "$it/api/swift/skie" })

            val stagedDirectory = layout.buildDirectory.dir("swift-dump")
            val committedDumpDirectory = dump.committedDumpPath.map { path ->
                rootProject.layout.projectDirectory.dir(path)
            }
            val generatedSwiftDirectory = layout.buildDirectory.dir(SKIE_GENERATED_SWIFT_LAYOUT)
            val validateLayout = tasks.register(
                "validateSkieSwiftLayout",
                ValidateSkieSwiftLayoutTask::class.java,
            ) {
                group = VERIFICATION_GROUP
                description = "Validates the pinned SKIE-generated Swift layout for ${dump.surfaceName.get()}."
                dependsOn("linkDebugFrameworkIosArm64")
                generatedSwiftRoot.set(generatedSwiftDirectory)
                supportedLayout.set(SKIE_GENERATED_SWIFT_LAYOUT)
            }
            val generate = tasks.register("generateSwiftDump", GenerateSkieSwiftDumpTask::class.java) {
                group = VERIFICATION_GROUP
                description = "Generates the sanitized SKIE dump for ${dump.surfaceName.get()}."
                dependsOn(validateLayout)
                doNotTrackState(
                    "SKIE does not expose the generated Swift directory as a declared task output.",
                )
                linkedHeader.set(
                    dump.frameworkName.flatMap { framework ->
                        layout.buildDirectory.file(
                            "bin/iosArm64/debugFramework/${framework}.framework/Headers/${framework}.h",
                        )
                    },
                )
                generatedSwiftRoot.set(generatedSwiftDirectory)
                supportedLayout.set(SKIE_GENERATED_SWIFT_LAYOUT)
                outputHeaderName.set(dump.outputHeaderName)
                outputSwiftName.set(dump.outputSwiftName)
                outputDirectory.set(stagedDirectory)
            }

            val refresh = tasks.register("refreshSwiftDump", Sync::class.java) {
                group = VERIFICATION_GROUP
                description = "Refreshes the committed SKIE dump for ${dump.surfaceName.get()}."
                dependsOn(generate)
                doNotTrackState("Refresh must always reconcile stale files in the committed dump.")
                from(stagedDirectory)
                into(committedDumpDirectory)
                includeEmptyDirs = false
            }

            tasks.register("checkSwiftDump", CheckSwiftDumpTask::class.java) {
                group = VERIFICATION_GROUP
                description = "Checks the committed SKIE dump for ${dump.surfaceName.get()}."
                dependsOn(generate)
                mustRunAfter(refresh)
                generatedFiles.from(stagedDirectory)
                committedFiles.from(committedDumpDirectory)
                generatedDirectory.set(stagedDirectory)
                committedDirectory.set(committedDumpDirectory)
                failureMessage.set(
                    dump.surfaceName.zip(dump.committedDumpPath) { surface, committedPath ->
                        "SKIE dump for $surface has drifted from $committedPath. " +
                            "Run ./gradlew refreshSwiftDumps and commit the result."
                    },
                )
            }
        }
    }
}

private fun sanitized(raw: String): String =
    raw.lineSequence()
        .filterNot { volatileVersionLine.containsMatchIn(it) }
        .joinToString("\n")
        .trimEnd() + "\n"

private fun unsupportedSkieLayout(swiftRoot: File, supportedLayout: String): GradleException =
    GradleException(
        "SKIE-generated Swift output is unavailable at ${swiftRoot.path}. " +
            "The pinned SKIE 0.10.13 layout '$supportedLayout' was not produced; " +
            "no supported SKIE output API is available.",
    )

private fun recreateDirectory(directory: File) {
    if (directory.exists() && !directory.deleteRecursively()) {
        throw GradleException("Could not clear Swift dump staging directory at ${directory.path}")
    }
    if (!directory.mkdirs() && !directory.isDirectory) {
        throw GradleException("Could not create Swift dump staging directory at ${directory.path}")
    }
}

private fun snapshot(directory: File): Map<String, ByteArray> {
    if (!directory.isDirectory) return emptyMap()

    return directory.walkTopDown()
        .filter(File::isFile)
        .associate { file -> file.relativeTo(directory).invariantSeparatorsPath() to file.readBytes() }
}

private fun StringBuilder.appendDifference(label: String, paths: List<String>) {
    if (paths.isNotEmpty()) {
        appendLine("$label: ${paths.joinToString()}")
    }
}

private fun File.invariantSeparatorsPath(): String = path.replace('\\', '/')

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `kotlin-dsl`
}

group = "org.mobilenativefoundation.store"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.binary.compatibility.validator)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.dokka.gradle.plugin)
    compileOnly(libs.kover.gradle.plugin)
    compileOnly(libs.maven.publish.plugin)
    compileOnly(libs.kmmBridge.gradle.plugin)
    compileOnly(libs.atomic.fu.gradle.plugin)

    testImplementation(kotlin("test", embeddedKotlinVersion))
    testImplementation(libs.junit)
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        register("store6MultiplatformConventionPlugin") {
            id = "org.mobilenativefoundation.store.store6.multiplatform"
            implementationClass = "org.mobilenativefoundation.store.tooling.plugins.Store6MultiplatformConventionPlugin"
        }

        register("store6MultiplatformSubsetConventionPlugin") {
            id = "org.mobilenativefoundation.store.store6.multiplatform.subset"
            implementationClass = "org.mobilenativefoundation.store.tooling.plugins.Store6MultiplatformSubsetConventionPlugin"
        }

        register("store6ObjcSwiftDumpPlugin") {
            id = "org.mobilenativefoundation.store.store6.swift-dump.objc"
            implementationClass = "org.mobilenativefoundation.store.tooling.plugins.Store6ObjcSwiftDumpPlugin"
        }

        register("store6SkieSwiftDumpPlugin") {
            id = "org.mobilenativefoundation.store.store6.swift-dump.skie"
            implementationClass = "org.mobilenativefoundation.store.tooling.plugins.Store6SkieSwiftDumpPlugin"
        }

        register("androidConventionPlugin") {
            id = "org.mobilenativefoundation.store.android"
            implementationClass = "org.mobilenativefoundation.store.tooling.plugins.AndroidConventionPlugin"
        }
    }
}

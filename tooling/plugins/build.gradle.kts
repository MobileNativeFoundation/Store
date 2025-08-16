plugins {
    `kotlin-dsl`
}

group = "org.mobilenativefoundation.store"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11

    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.dokka.gradle.plugin)
    compileOnly(libs.maven.publish.plugin)
    compileOnly(libs.atomic.fu.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("kotlinMultiplatformConventionPlugin") {
            id = "org.mobilenativefoundation.store.multiplatform"
            implementationClass = "org.mobilenativefoundation.store.tooling.plugins.KotlinMultiplatformConventionPlugin"
        }

        register("androidConventionPlugin") {
            id = "org.mobilenativefoundation.store.android"
            implementationClass = "org.mobilenativefoundation.store.tooling.plugins.AndroidConventionPlugin"
        }
    }
}
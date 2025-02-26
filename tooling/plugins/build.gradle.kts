import org.gradle.internal.impldep.org.eclipse.jgit.util.RawCharUtil.trimTrailingWhitespace
import org.jetbrains.kotlin.builtins.StandardNames.FqNames.target

plugins {
	`kotlin-dsl`
	alias(libs.plugins.spotless)
	alias(libs.plugins.detekt)
}

group = "org.mobilenativefoundation.store"

java {
	sourceCompatibility = JavaVersion.VERSION_11
	targetCompatibility = JavaVersion.VERSION_11

	toolchain {
		languageVersion.set(JavaLanguageVersion.of(11))
	}
}

spotless {
	kotlin {
		ktfmt(libs.versions.ktfmt.get()).googleStyle()
		target("src/**/*.kt")
		trimTrailingWhitespace()
		endWithNewline()
	}

	kotlinGradle {
		ktfmt(libs.versions.ktfmt.get()).googleStyle()
		target("*.kts")
		trimTrailingWhitespace()
		endWithNewline()
	}
}

detekt {
	buildUponDefaultConfig = true
	baseline = file("../config/detekt/baseline.xml")
	config.setFrom("../../config/detekt/rules.yml")
	source.setFrom("src")
}

dependencies {
	compileOnly(libs.android.gradle.plugin)
	compileOnly(libs.kotlin.gradle.plugin)
	compileOnly(libs.dokka.gradle.plugin)
	compileOnly(libs.maven.publish.plugin)
	compileOnly(libs.kmmBridge.gradle.plugin)
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
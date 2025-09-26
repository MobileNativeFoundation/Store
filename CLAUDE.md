# Linting and Formatting Migration

This document describes the migration from ktlint to ktfmt + detekt for code formatting and linting.

## Migration Summary

The project has been migrated from:
- **ktlint** (formatting + linting)
- To: **ktfmt** (formatting) + **detekt** (linting)

This change maintains all existing lint checks while providing better integration and more configuration options.

## New Tools

### ktfmt (Formatting)
- **Version**: 0.23.0 (via com.ncorti.ktfmt.gradle plugin)
- **Purpose**: Code formatting only
- **Configuration**: Uses Google style (similar to ktlint)
- **Commands**:
  - `./gradlew ktfmtFormat` - Format all code
  - `./gradlew ktfmtCheck` - Check if code is properly formatted

### detekt (Static Analysis)
- **Version**: 1.23.8
- **Purpose**: Static code analysis and linting
- **Configuration**: `config/detekt/detekt.yml`
- **Commands**:
  - `./gradlew detekt` - Run static analysis
  - `./gradlew detektMain` - Run on main source code only

## Configuration Files

### Version Catalog (`gradle/libs.versions.toml`)
```toml
[versions]
ktfmtGradle = "0.23.0"
detekt = "1.23.8"

[plugins]
ktfmt = {id = "com.ncorti.ktfmt.gradle", version.ref = "ktfmtGradle"}
detekt = {id = "io.gitlab.arturbosch.detekt", version.ref = "detekt"}
```

### Main Build Script (`build.gradle.kts`)
```kotlin
plugins {
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.detekt)
    // ...
}

subprojects {
    apply(plugin = "com.ncorti.ktfmt.gradle")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    ktfmt {
        googleStyle() // Similar to ktlint formatting
    }

    detekt {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(file("$rootDir/config/detekt/detekt.yml"))
    }
}
```

### Detekt Configuration (`config/detekt/detekt.yml`)
- Comprehensive configuration with sensible defaults
- Disabled rules that conflict with ktfmt formatting
- Test exclusions for many rules
- Configured for Kotlin multiplatform projects

## CI/CD Integration

The GitHub Actions CI workflow has been updated to run both tools:

```yaml
- name: Build and Test with Coverage
  run: ./gradlew clean ktfmtCheck detekt build koverXmlReport --stacktrace
```

## Developer Workflow

### Before Committing
Run the formatting and linting checks:
```bash
./gradlew ktfmtFormat detekt
```

### IDE Integration
Both ktfmt and detekt have excellent IDE support:
- **IntelliJ IDEA**: Built-in support for both tools
- **ktfmt**: Use the ktfmt IntelliJ plugin for automatic formatting
- **detekt**: Use the detekt IntelliJ plugin for live analysis

### Common Commands

| Task | Command |
|------|---------|
| Format all code | `./gradlew ktfmtFormat` |
| Check formatting | `./gradlew ktfmtCheck` |
| Run linting | `./gradlew detekt` |
| Format + Lint | `./gradlew ktfmtFormat detekt` |
| Full CI check | `./gradlew clean ktfmtCheck detekt build` |

## Migration Notes

1. **Import ordering**: Previously disabled in ktlint, now handled by ktfmt's Google style
2. **Rule parity**: Most ktlint rules have equivalent detekt rules
3. **Performance**: ktfmt is generally faster than ktlint for formatting
4. **Configuration**: detekt offers more granular configuration options than ktlint

## Troubleshooting

### Format conflicts
If ktfmt and spotless conflict, run ktfmt first:
```bash
./gradlew ktfmtFormat spotlessApply
```

### Detekt configuration issues
Check the detekt.yml configuration file and ensure all rule sets are properly configured for your project structure.
# bom

`org.mobilenativefoundation.store:bom` is a Maven BOM (packaging `pom`). Importing it as a
platform dependency pins every Store 6 artifact of the same release to one version, so you
align the modules you use without stating each version:

```kotlin
implementation(platform("org.mobilenativefoundation.store:bom:6.0.0-SNAPSHOT"))
```

The BOM has no API surface and declares no dependencies; it only constrains versions of the
artifacts that ship in the corresponding release (STABILITY.md §3). An artifact joins the BOM
in the release that first ships it.

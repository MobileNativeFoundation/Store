# Releasing

1. Set `store` in `gradle/libs.versions.toml` to the new version. The Android and
   Kotlin Multiplatform conventions use it for Maven publications, and the
   Multiplatform convention also uses it for CocoaPods.
2. Update `CHANGELOG.md`, including the release link and `Unreleased` comparison.
3. Validate the build tooling and generated publication metadata before opening
   the release PR:

   ```sh
   ./gradlew -p tooling :plugins:test :plugins:ktlintCheck
   ./gradlew verifyPublicationVersions
   ```

   The metadata check validates generated POMs and Gradle module metadata,
   including internal dependency versions and target redirects. Host-disabled
   metadata generators can skip execution. Validate on macOS as well as Linux
   to cover Apple publications. Existing files from skipped tasks are not checked.
4. Commit with a DCO sign-off and open a PR. Require passing tests, API and
   formatting checks, and CI on the release commit. Record JVM and Native test
   execution separately.
5. Merge the approved release PR. A successful push build on `main` automatically
   publishes to Maven Central. Non-SNAPSHOT versions use
   `publishAndReleaseToMavenCentral`; SNAPSHOT versions use `publishToMavenCentral`.
   The macOS publication job verifies metadata before uploading artifacts.
6. Verify that consumers can resolve the new Maven coordinates and their internal
   dependencies after publication completes.
7. For Swift distribution, run the **KMMBridge-Publish** workflow for the tested
   release commit. Its reusable workflow reads the same catalog version, creates
   or finds the GitHub release, publishes XCFrameworks, and updates the release tag.
   Mark alpha and beta GitHub releases as prereleases and verify the Swift package
   resolves the intended artifacts.

Do not reuse an already-published version. Prepare any subsequent development
version through another PR, accounting for automatic publication on `main`.

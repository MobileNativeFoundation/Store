Releasing
========

1. Change the version in top level `gradle.properties` to a non-SNAPSHOT version.
2. Update the `cocoapods` version in `build.gradle.kts` in `:store`.
3. Update the `CHANGELOG.md` for the impending release.
4. Update the `README.md` with the new version.
5. `git commit -sam "Prepare for release X.Y.Z."` (where X.Y.Z is the new version)
6. `git tag -a X.Y.X -m "Version X.Y.Z"` (where X.Y.Z is the new version)
    * Run `git tag` to verify it.
7. `git push && git push --tags`
    * This should be pushed to your fork.
8. Create a PR with this commit and merge it.
9. Update the top level `build.gradle` to the next SNAPSHOT version.
10. `git commit -am "Prepare next development version."`
11. Create a PR with this commit and merge it.
12. Login to Sonatype to promote the artifacts https://central.sonatype.org/pages/releasing-the-deployment.html
    * This part is automated. If it fails in CI, follow the steps below.
    * Click on Staging Repositories under Build Promotion
    * Select all the Repositories that contain the content you want to release
    * Click on Close and refresh until the Release button is active
    * Click Release and submit
13. Update the sample module's `build.gradle` to point to the newly released version. (It may take ~2 hours for artifact to be available after release)
 
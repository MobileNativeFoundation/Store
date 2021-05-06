Releasing
========

 1. Change the version in top level `build.gradle` to a non-SNAPSHOT verson.
 2. Update the `CHANGELOG.md` for the impending release.
 3. Update the `README.md` with the new version.
 4. `git commit -am "Prepare for release X.Y.Z."` (where X.Y.Z is the new version)
 5. `git tag -a X.Y.X -m "Version X.Y.Z"` (where X.Y.Z is the new version)
    * Run `git tag` to verify it.
 6. `git push && git push --tags` 
    * This should be pushed to your fork.
 7. Create a PR with this commit and merge it.
 8. Update the top level `build.gradle` to the next SNAPSHOT version.
 9. `git commit -am "Prepare next development version."`
 10. Create a PR with this commit and merge it.
 11. Login to Sonatype to promote the artifacts https://central.sonatype.org/pages/releasing-the-deployment.html
      * Click on Staging Repositories under Build Promotion
      * Select all the Repositories that contain the content you want to release
      * Click on Close and refresh until the Release button is active
      * Click Release and submit
 12. Update `dependencies.gradle` to point to the newly released version of `cache`. (It may take ~2 hours for artifact to be available after release)


**Note:** We are currently not pinning the sample app to the last version because the API is still fluid while `Store` is in alpha. We will resume pinning the sample app to a released version when we move to beta (see #159).

When we're ready to pin, restore the final step:

13. Update the sample module's `build.gradle` to point to the newly released version. (It may take ~2 hours for artifact to be available after release)
 
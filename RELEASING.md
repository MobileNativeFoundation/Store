Releasing
========

 1. Change the version in top level `build.gradle` to a non-SNAPSHOT verson.
 2. Update the `CHANGELOG.md` for the impending release.
 3. Update the `README.md` with the new version.
 4. `git commit -am "Prepare for release X.Y.Z."` (where X.Y.Z is the new version)
 5. `git tag -a X.Y.X -m "Version X.Y.Z"` (where X.Y.Z is the new version)
 8. Update the top level `build.gradle` to the next SNAPSHOT version.
 9. `git commit -am "Prepare next development version."`
 10. `git push && git push --tags`
 11. Create a PR with these 2 commits.
     * **IMPORTANT** Add this comment to your PR "This is a release PR, it must be merged as individual commits. Do not squash commits on merge"
     * Longer explanation: we release automatically through Travis CI. When Travis builds on master a script is run to send either a new shapshot or a new release version to Maven. If you squash the commits in the PR, Travis will only see what's left at the end, which is your commit to change back to `SNAPSHOT` release. Thus, Travis will not end up sending a release version to Maven. If you land as multiple commits, Travis will build both and send a release build to Maven for the commit where you bumped the version to a new release version.
 11. Update the sample module to point to the newly released version. (May take 2 hours)

If step 5 or 6 fails, drop the Sonatype repo, fix the problem, commit, and start again at step 5.

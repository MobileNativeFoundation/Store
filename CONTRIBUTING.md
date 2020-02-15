Contributing to Store
=======================

The Dropbox team welcomes contributions of all kinds, from simple bug reports through documentation, test cases,
bugfixes, and features.

DOs and DON'Ts
--------------
* Do sign our CLA located here: https://opensource.dropbox.com/cla/
* DO follow our coding style (as described below)
* DO give priority to the current style of the project or file you're changing even if it diverges from the general guidelines.
* DO include tests when adding new features. When fixing bugs, start with adding a test that highlights how the current behavior is broken.
* DO keep the discussions focused. When a new or related topic comes up it's often better to create new issue than to side track the discussion.
* DO run all Gradle verification tasks (`./gradlew check`) before submitting a pull request

* DO NOT send PRs for style changes.
* DON'T surprise us with big pull requests. Instead, file an issue and start a discussion so we can agree on a direction before you invest a large amount of time.
* DON'T commit code that you didn't write. If you find code that you think is a good fit, file an issue and start a discussion before proceeding.
* DON'T submit PRs that alter licensing related files or headers. If you believe there's a problem with them, file an issue and we'll be happy to discuss it.


Coding Style
------------

The coding style employed here is fairly conventional Java - indentations are four spaces, class
names are PascalCased, identifiers and methods are camelCased.

We use [ktlint](https://github.com/pinterest/ktlint) with the [ktlint gradle plugin](https://github.com/JLLeitschuh/ktlint-gradle) for Kotlin code formatting.
To make sure the IDE agrees with rules we use, please run `./gradlew ktlintApplyToIdea` to generate IntelliJ IDEA / Android Studio Kotlin style files in the project .idea/ folder.    

Workflow
--------

We love Github issues!  Before working on any new features, please open an issue so that we can agree on the
direction, and hopefully avoid investing a lot of time on a feature that might need reworking.

Small pull requests for things like typos, bugfixes, etc are always welcome.

Please note that we will not accept pull requests for style changes.

We use the [binary-compatibility-validator plugin](https://github.com/Kotlin/binary-compatibility-validator) for tracking the binary compatibility of the APIs we ship.
If your change implies changes to any public API, run `./gradlew apiDump` to generate the updated API dumps and commit those changes.

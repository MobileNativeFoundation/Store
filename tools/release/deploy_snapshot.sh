#!/bin/bash
#
# Deploy a jar, source jar, and javadoc jar to Sonatype's snapshot repo.
#
# Adapted from https://coderwall.com/p/9b_lfq and
# http://benlimmer.com/2013/12/26/automatically-publish-javadoc-to-gh-pages-with-travis-ci/

SLUG="dropbox/Store"
JDK="oraclejdk8"
BRANCH="main"

set -e

if [ "$TRAVIS_REPO_SLUG" != "$SLUG" ]; then
  echo "Skipping snapshot deployment: wrong repository. Expected '$SLUG' but was '$TRAVIS_REPO_SLUG'."
elif [ "$TRAVIS_JDK_VERSION" != "$JDK" ]; then
  echo "Skipping snapshot deployment: wrong JDK. Expected '$JDK' but was '$TRAVIS_JDK_VERSION'."
elif [ "$TRAVIS_PULL_REQUEST" != "false" ]; then
  echo "Skipping snapshot deployment: was pull request."
elif [ "$TRAVIS_BRANCH" != "$BRANCH" ]; then
  echo "Skipping snapshot deployment: wrong branch. Expected '$BRANCH' but was '$TRAVIS_BRANCH'."
else
  echo "Deploying store..."
  openssl aes-256-cbc -md sha256 -d -in tools/release/secring.gpg.aes -out tools/release/secring.gpg -k "${ENCRYPT_KEY}"
  # https://docs.gradle.org/current/userguide/signing_plugin.html#sec:signatory_credentials
  ./gradlew uploadArchives -PSONATYPE_USERNAME="${SONATYPE_USERNAME}" -PSONATYPE_PASSWORD="${SONATYPE_PASSWORD}" -Psigning.keyId="${SIGNING_ID}" -Psigning.password="${SIGNING_PASSWORD}" -Psigning.secretKeyRingFile=${PWD}/tools/release/secring.gpg
  echo "Store deployed!"
fi

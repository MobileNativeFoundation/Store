plugins {
    `java-platform`
    id("com.vanniktech.maven.publish.base")
}

// Publication coordinates come from gradle properties (GROUP, POM_ARTIFACT_ID,
// VERSION_NAME), matching every other store6 module. Project constraints are spelled
// out as coordinates for the same reason: the library modules leave project.group /
// project.version at their Gradle defaults and only the publisher maps them onto the
// property values, so a project("") constraint here would publish the wrong version.
// VERSION_NAME lives only in the root gradle.properties, so the constraint versions
// below and every module's publication coordinates resolve the same single value.

dependencies {
    constraints {
        val version = providers.gradleProperty("VERSION_NAME").get()
        val group = providers.gradleProperty("GROUP").get()

        api("$group:core:$version")
        api("$group:testing:$version")
        api("$group:sqldelight:$version")
        api("$group:room:$version")
        api("$group:compose:$version")
        api("$group:graphql:$version")
        api("$group:realtime:$version")
        api("$group:mutations:$version")
        api("$group:mutations-testing:$version")
        api("$group:mutations-sqldelight:$version")
        api("$group:paging-androidx:$version")
        api("$group:opentelemetry:$version")
        api("$group:ktor:$version")
        api("$group:file:$version")
        api("$group:mutations-conflicts:$version")
    }
}

configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
    configure(com.vanniktech.maven.publish.JavaPlatform())
    publishToMavenCentral(automaticRelease = true)

    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pomFromGradleProperties()
}

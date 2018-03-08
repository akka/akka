## Releasing
1. If this is a new minor (not patch) release, rename the 'akka-http-x.x-stable' reporting project in [WhiteSource](https://saas.whitesourcesoftware.com/) accordingly
1. Communicate that a release is about to be released in [Gitter Akka Dev Channel](https://gitter.im/akka/dev), so that no new Pull Requests are merged
1. Add a release notes entry in docs/src/main/paradox/scala/http/release-notes.md. As a helper run
`scripts/commits-for-release-notes.sh <last-version-tag>` which will output a list of commits grouped by submodule, and the closed issues for this milestone.
1. Update latest Akka 2.5 version variable `akka25Version` in `project/Dependencies.scala`
1. Create a news item on https://github.com/akka/akka.github.com, using the milestones and `scripts/authors.scala previousVersion thisVersion`
1. Once the release notes have been merged, create a [new release](https://github.com/akka/akka-http/releases/new) with the next tag version (e.g. `v13.3.7`), title and release description linking to announcement, release notes and milestone.
1. Travis CI will start a [CI build](https://travis-ci.org/akka/akka-http/builds) for the new tag and publish artifacts to Bintray.
1. Checkout the newly created tag and run `sbt -Dakka.genjavadoc.enabled=true "++2.12.4 deployRsync akkarepo@gustav.akka.io"` to deploy API and reference documentation.
1. Go to https://bintray.com/akka/maven/com.typesafe.akka:akka-http_2.11 and select the just released version
1. Go to the Maven Central tab and sync with Sonatype
   - (Optional, should happen automatically if selected in Bintray) Log in to Sonatype to Close the staging repository
   - Run a test against the staging repository to make sure the release went well, for examply by using https://github.com/akka/akka-http-scala-seed.g8 and adding the sonatype staging repo with `resolvers += "Staging Repo" at "https://oss.sonatype.org/content/repositories/comtypesafe-xxx"`
   - Release the staging repository to Maven Central.
1. Create a new milestone for the next version at https://github.com/akka/akka-http/milestones , move all unclosed issues there and close the version you're releasing
1. Add the released version to `project/MiMa.scala` to the `mimaPreviousArtifacts` key.
1. Send a release notification to akka-user and tweet using the akka account (or ask someone to) about the new release
1. Log into gustav.akka.io as akkarepo and update the `current` links on repo.akka.io to point to the latest version with `ln -nsf <latestversion> www/docs/akka-http/current; ln -nsf <latestversion> www/api/akka-http/current; ln -nsf <latestversion> www/japi/akka-http/current`.

### Follow up steps

1. Update Akka HTTP dependency on akka.io website: https://github.com/akka/akka.github.com/blob/master/_config.yml
2. Update Akka HTTP dependency in akka-http seed templates ([scala](https://github.com/akka/akka-http-scala-seed.g8/) & [java](https://github.com/akka/akka-http-java-seed.g8/)) 
3. Update Akka HTTP dependency in [akka-management](https://github.com/akka/akka-management/blob/master/project/Dependencies.scala)
4. Update Akka HTTP reference in [reactive-platform-docs](https://github.com/typesafehub/reactive-platform-docs/blob/master/build.sbt#L29)

### Under the Travis hood

Here is what happens in detail when Travis CI is building a git tagged commit:

1. According to the `.travis.yml` file `deploy` section it first runs a `+publish` task:
  1. Because of the `+` a the start of the task, all of these actions are repeated for every scala version defined in `crossScalaVersions`.
  2. Because of the `bintray-sbt` plugin, `publish` builds and uploads artifacts to Bintray.
  3. By default all projects have `BintrayPlugin` enabled. Projects that have `disablePlugins(BintrayPlugin)` are not built.
  4. Artifacts are uploaded to `https://bintray.com/$bintrayOrganization/$bintrayRepository/$bintrayPackage` which in this case is [https://bintray.com/akka/maven/akka-http](https://bintray.com/akka/maven/akka-http).
  5. Credentials for the `publish` task are read from the `BINTRAY_USER` and `BINTRAY_PASS` environment variables which are stored encrypted on the `.travis.yml` file. The user under these credentials must be part of the [Akka team on Bintray](https://bintray.com/akka/).
  
# Pushing to Maven Central
This could be done automatically via `.travis.yml` in `deploy` by adding section is `akka-http/bintraySyncMavenCentral`, however we prefer to run this step manually after confirming a release is valid.

  1. This task syncs all of the artifacts under the [Akka Http](https://bintray.com/akka/maven/akka-http) package in Bintray to Maven Central. For the sync to be successful, the package first needs to be added to JCenter repository. This must be done through Bintray Web interface, and only once when the package is created.
  2. This task is only ran for one project, because all Akka Http projects are published to a single package on Bintray.
  3. Credentials for the `bintraySyncMavenCentral` task are read from the `SONATYPE_USER` and `SONATYPE_PASS` environment variables which are stored encrypted on the `.travis.yml` file. The user under these credentials must have the rights to publish artifacts to the Maven Central under the `com.typesafe.akka` organization name.

# Releasing

## Prerequisites

### JDK 8 and JDK 11

Releasing Akka requires running on JDK 11, but also having JDK 8
installed. The reason for this is that we want the Akka artifacts to be
usable with JRE 8, but also want to compile some classes with JDK11-specific
types.

In the future we might be able to update the build to work
without having JDK 8 installed, by using the `-release` option.

### One Time GPG and sbt-pgp setup

If you have not set up GPG or used `sbt-pgp` on the release machine
* Check the [sbt-pgp usage](https://www.scala-sbt.org/sbt-pgp/usage.html) for any setup steps you may still need, for example:
```
sbt> set pgpReadOnly := false
sbt> pgp-cmd gen-key
```    
* Check that signing works with `sbt> publishLocalSigned`
   
#### Mac

When releasing from MacOS you may want to use YubiKey or have [MacGPG](https://gpgtools.org) installed.

#### Windows

When releasing from Windows, you need MinGW and a gpg distribution such as Gpg4Win

### Git

Make sure you have set `core.autocrlf` to `false` in your `~/.gitconfig`,
otherwise git might convert line endings in some cases.

### Whitesource

Make sure you have the Lightbend Whitesource credentials configured in
your `~/.sbt/1.0/private-credentials.sbt`.

### Install Graphviz

[Graphvis](https://graphviz.gitlab.io/download/) is needed for the 
scaladoc generation build task, which is part of the release.
 
### Release script instructions

Make sure you have completed the setup in `project/scripts/release`.

## Snapshot releases

Nightly snapshot releases are created from master and published to
https://repo.akka.io/snapshots by https://jenkins.akka.io:8498/job/akka-publish-nightly/

To create snapshot versions manually, use `sbt clean publish`.
The release artifacts are created in `akka-*/target/repository` and can be
copied over to a maven server. If you have access, the Jenkins job at
https://jenkins.akka.io:8498/job/akka-publish-wip/ can be used to publish
a snapshot to https://repo.akka.io/snapshots from any branch.

## Releasing only updated docs

It is possible to release a revised documentation to the already existing release.

1. Create a new branch from a release tag. If a revised documentation is for the `v2.6.4` release, then the name of the new branch should be `docs/v2.6.4`.
1. Add and commit `version.sbt` file that pins the version to the one that is being revised. Also set `isSnapshot` to `false` for the stable documentation links. For example:
    ```scala
    ThisBuild / version := "2.6.4"
    ThisBuild / isSnapshot := false
    ```
1. Make all of the required changes to the documentation.
1. Build documentation locally with:
    ```sh
    sbt akka-docs/paradoxBrowse
    ```
1. If the generated documentation looks good, send it to Gustav:
    ```sh
    sbt akka-docs/publishRsync
    ```
1. Do not forget to push the new branch back to GitHub.

## Release steps

* Tag the release: `git tag -am "Version 2.6.x" v2.6.x`
* Do a `project/scripts/release` dry run
* If all goes well, `project/scripts/release --real-run`
* Log into sonatype, 'close' the staging repo.
* Test the artifacts by adding `resolvers += "Staging Repo" at "https://oss.sonatype.org/content/repositories/comtypesafe-xxxx"` to a test project
* If all is well, 'release' the staging repo.
* Push the release tag to github

## Announcing

* Prepare milestone on github:
  * go to the [Milestones tab](https://github.com/akka/akka/milestones)
  * move all open issues so that this milestone contains completed work only
  * close that milestone
  * create a new milestone for next patch version

* In case of a new minor release:
  * update the branch descriptions at CONTRIBUTING.md#branches-summary
  * write blog post for akka.io and lightbend.com

* Create an announcement as a PR against akka/akka.github.com .
  * credits can be generated with `scripts/authors.scala v2.3.5 v2.3.6`
  * also update the `latest` variable in `_config.yml`.

Now wait until all artifacts have been properly propagated. Then:

* Update `MiMa.latestPatchOf` and PR that change (`project/MiMa.scala`)

* Change the symbolic links from 'current': `ssh akkarepo@gustav.akka.io ./update-akka-current-version.sh <x.y.z>`

* Publish the release announcement
  * Tweet about it
  * Post about it on Discuss
  * Post about it on Gitter

## Update references

Update the versions used in:

* https://github.com/akka/akka-samples
* https://github.com/lightbend/lightbend-platform-docs/blob/master/docs/modules/getting-help/examples/build.sbt (this populates https://developer.lightbend.com/docs/lightbend-platform/introduction/getting-help/build-dependencies.html#_akka)

These are autoupdated by latest stable on maven central:
* https://github.com/akka/akka-quickstart-java.g8
* https://github.com/akka/akka-quickstart-scala.g8
* https://github.com/akka/akka-http-quickstart-java.g8
* https://github.com/akka/akka-http-quickstart-scala.g8
* https://github.com/akka/akka-grpc-quickstart-java.g8
* https://github.com/akka/akka-grpc-quickstart-scala.g8

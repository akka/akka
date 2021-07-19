# Releasing

Create a new issue from the [Release Train Issue Template](scripts/release-train-issue-template.md):

```
$ sh ./scripts/create-release-issue.sh 0.x.y
```

# Manually

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
 
## Snapshot releases

Snapshot releases are created from master and published to
https://oss.sonatype.org/content/repositories/snapshots/com/typesafe/akka/

To create snapshot versions manually, use `sbt clean publishLocal`.
If you have access, you can use `+publishSigned` to publish them to
sonatype.

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

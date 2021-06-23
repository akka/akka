Release Akka $VERSION$

### Before the release

- [ ] Make sure all important / big PRs have been merged by now
- [ ] Create a news item draft PR on [akka.io](https://github.com/akka/akka.io), using the milestone and `scripts/authors.scala v2.3.5 v2.3.6`
- [ ] Make sure to update `_config.yml` in it
- In case of a new minor release:
  - [ ] update the branch descriptions at CONTRIBUTING.md#branches-summary

### Cutting the release

- [ ] Make sure any running [actions](https://github.com/akka/akka/actions) for the commit you would like to release have completed.
- [ ] Tag the release `git tag -a -s -m 'Release v$VERSION$' v$VERSION$` and push the tag `git push --tags`
- [ ] Create a [new milestone](https://github.com/akka/akka/milestones) for the next version and close the current one.
- [ ] Check that the GitHub Actions release build has executed successfully (it should publish artifacts to Sonatype and documentation to Gustav)
- [ ] Update `MiMa.latestPatchOf` and PR that change (`project/MiMa.scala`)

### Check availability

- [ ] Check [reference](https://doc.akka.io/docs/akka/$VERSION$/) documentation
- [ ] Check the release on [Maven central](https://repo1.maven.org/maven2/com/typesafe/akka/akka-actor_2.13/$VERSION$/)

### When everything is on maven central

- [ ] `ssh akkarepo@gustav.akka.io`
  - [ ] update the `current` links on `repo.akka.io` to point to the latest version with
       ```
       ln -nsf $VERSION$ www/docs/akka/current
       ln -nsf $VERSION$ www/api/akka/current
       ln -nsf $VERSION$ www/japi/akka/current
       ```
  - [ ] check changes and commit the new version to the local git repository
       ```
       cd ~/www
       git add docs/akka/current docs/akka/$VERSION$
       git add api/akka/current api/akka/$VERSION$
       git add japi/akka/current japi/akka/$VERSION$
       git commit -m "Akka $VERSION$"
       ```
  - [ ] push changes to the [remote git repository](https://github.com/akka/doc.akka.io)
       ```
       cd ~/www
       git push origin master
       ```

### Announcements

- [ ] Merge draft news item for [akka.io](https://github.com/akka/akka.github.com)
- [ ] Create a [GitHub release](https://github.com/akka/akka/releases) with the next tag version `v$VERSION$`, title and a link to the announcement
- [ ] Post about it on the [forum](https://discuss.akka.io)
- [ ] Tweet using the [@akkateam](https://twitter.com/akkateam) account (or ask someone to) about the new release
- [ ] Announce on [Gitter akka/akka](https://gitter.im/akka/akka)
- [ ] Announce internally

## Update references

Update the versions used in:

* [ ] https://github.com/akka/akka-samples
* [ ] https://github.com/lightbend/lightbend-platform-docs/blob/master/docs/modules/getting-help/examples/build.sbt (this populates https://developer.lightbend.com/docs/lightbend-platform/introduction/getting-help/build-dependencies.html#_akka)

These are autoupdated by latest stable on maven central:
* https://github.com/akka/akka-quickstart-java.g8
* https://github.com/akka/akka-quickstart-scala.g8
* https://github.com/akka/akka-http-quickstart-java.g8
* https://github.com/akka/akka-http-quickstart-scala.g8
* https://github.com/akka/akka-grpc-quickstart-java.g8
* https://github.com/akka/akka-grpc-quickstart-scala.g8

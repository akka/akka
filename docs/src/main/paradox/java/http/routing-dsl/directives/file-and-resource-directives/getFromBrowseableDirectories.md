# getFromBrowseableDirectories

@@@ div { .group-scala }

## Signature

@@signature [FileAndResourceDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/FileAndResourceDirectives.scala) { #getFromBrowseableDirectories }

@@@

## Description

The `getFromBrowseableDirectories` is a combination of serving files from the specified directories
(like `getFromDirectory`) and listing a browseable directory with `listDirectoryContents`.

Nesting this directive beneath `get` is not necessary as this directive will only respond to `GET` requests.

Use `getFromBrowseableDirectory` to serve only one directory.

Use `getFromDirectory` if directory browsing isn't required.

For more details refer to @ref[getFromBrowseableDirectory](getFromBrowseableDirectory.md).

## Example

Scala
:  @@snip [FileAndResourceDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/FileAndResourceDirectivesExamplesSpec.scala) { #getFromBrowseableDirectories-examples }

Java
:  @@snip [FileAndResourceDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/FileAndResourceDirectivesExamplesTest.java) { #getFromBrowseableDirectories }

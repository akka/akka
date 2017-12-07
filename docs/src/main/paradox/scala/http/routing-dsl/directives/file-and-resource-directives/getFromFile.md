# getFromFile

@@@ div { .group-scala }

## Signature

@@signature [FileAndResourceDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/FileAndResourceDirectives.scala) { #getFromFile }

@@@

## Description

Allows exposing a file to be streamed to the client issuing the request.

The `unmatchedPath` (see @ref[extractUnmatchedPath](../basic-directives/extractUnmatchedPath.md)) of the @unidoc[RequestContext] is first transformed by
the given `pathRewriter` function, before being appended to the given directory name to build the final file name.

To files from a given directory use @ref[getFromDirectory](getFromDirectory.md).
To serve browsable directory listings use @ref[getFromBrowseableDirectories](getFromBrowseableDirectories.md).
To serve files from a classpath directory use @ref[getFromResourceDirectory](getFromResourceDirectory.md) instead.

Note that it's not required to wrap this directive with `get` as this directive will only respond to `GET` requests.

@@@ note
The file's contents will be read using an Akka Streams *Source* which *automatically uses
a pre-configured dedicated blocking io dispatcher*, which separates the blocking file operations from the rest of the stream.

Note also that thanks to using Akka Streams internally, the file will be served at the highest speed reachable by
the client, and not faster – i.e. the file will *not* end up being loaded in full into memory before writing it to
the client.
@@@

## Example

Scala
:  @@snip [FileAndResourceDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/FileAndResourceDirectivesExamplesSpec.scala) { #getFromFile-examples }

Java
:  @@snip [FileAndResourceDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/FileAndResourceDirectivesExamplesTest.java) { #getFromFile }

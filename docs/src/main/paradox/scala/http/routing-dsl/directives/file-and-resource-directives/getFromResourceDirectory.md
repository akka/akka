<a id="getfromresourcedirectory"></a>
# getFromResourceDirectory

## Signature

@@signature [FileAndResourceDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/FileAndResourceDirectives.scala) { #getFromResourceDirectory }

## Description

Completes GET requests with the content of the given classpath resource directory.

For details refer to @ref[getFromDirectory](getFromDirectory.md#getfromdirectory) which works the same way but obtaining the file from the filesystem
instead of the applications classpath.

Note that it's not required to wrap this directive with `get` as this directive will only respond to `GET` requests.

## Example

@@snip [FileAndResourceDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/FileAndResourceDirectivesExamplesSpec.scala) { #getFromResourceDirectory-examples }
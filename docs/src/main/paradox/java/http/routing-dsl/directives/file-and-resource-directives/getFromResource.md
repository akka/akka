<a id="getfromresource-java"></a>
# getFromResource

## Description

Completes `GET` requests with the content of the given classpath resource.

For details refer to @ref[getFromFile](getFromFile.md#getfromfile-java) which works the same way but obtaining the file from the filesystem
instead of the applications classpath.

Note that it's not required to wrap this directive with `get` as this directive will only respond to `GET` requests.

## Example

@@snip [FileAndResourceDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/FileAndResourceDirectivesExamplesTest.java) { #getFromResource }
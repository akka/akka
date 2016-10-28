<a id="withlog-java"></a>
# withLog

## Description

Allows running an inner route using an alternative `LoggingAdapter` in place of the default one.

The logging adapter can be extracted in an inner route using @ref[extractLog](extractLog.md#extractlog-java) directly,
or used by directives which internally extract the materializer without surfacing this fact in the API.

## Example

@@snip [BasicDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #withLog }
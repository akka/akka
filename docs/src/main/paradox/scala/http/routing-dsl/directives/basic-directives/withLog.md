<a id="withlog"></a>
# withLog

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #withLog }

## Description

Allows running an inner route using an alternative `LoggingAdapter` in place of the default one.

The logging adapter can be extracted in an inner route using @ref[extractLog](extractLog.md#extractlog) directly,
or used by directives which internally extract the materializer without surfacing this fact in the API.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #withLog0 }
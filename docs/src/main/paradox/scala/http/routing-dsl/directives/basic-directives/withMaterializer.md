<a id="withmaterializer"></a>
# withMaterializer

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #withMaterializer }

## Description

Allows running an inner route using an alternative `Materializer` in place of the default one.

The materializer can be extracted in an inner route using @ref[extractMaterializer](extractMaterializer.md#extractmaterializer) directly,
or used by directives which internally extract the materializer without surfacing this fact in the API
(e.g. responding with a Chunked entity).

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #withMaterializer-0 }
<a id="extractmaterializer"></a>
# extractMaterializer

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #extractMaterializer }

## Description

Extracts the `Materializer` from the `RequestContext`, which can be useful when you want to run an
Akka Stream directly in your route.

See also @ref[withMaterializer](withMaterializer.md#withmaterializer) to see how to customise the used materializer for specific inner routes.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #extractMaterializer-0 }
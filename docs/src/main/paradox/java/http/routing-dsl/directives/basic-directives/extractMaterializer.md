<a id="extractmaterializer-java"></a>
# extractMaterializer

## Description

Extracts the `Materializer` from the `RequestContext`, which can be useful when you want to run an
Akka Stream directly in your route.

See also @ref[withMaterializer](withMaterializer.md#withmaterializer-java) to see how to customise the used materializer for specific inner routes.

## Example

@@snip [BasicDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #extractMaterializer }
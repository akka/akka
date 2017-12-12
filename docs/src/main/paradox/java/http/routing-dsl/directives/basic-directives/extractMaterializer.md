# extractMaterializer

## Description

Extracts the @unidoc[Materializer] from the @unidoc[RequestContext], which can be useful when you want to run an
Akka Stream directly in your route.

See also @ref[withMaterializer](withMaterializer.md) to see how to customise the used materializer for specific inner routes.

## Example

@@snip [BasicDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #extractMaterializer }
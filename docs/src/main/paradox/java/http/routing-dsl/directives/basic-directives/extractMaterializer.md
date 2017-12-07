# extractMaterializer

@@@ div { .group-scala }

## Signature

@@signature [BasicDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #extractMaterializer }

@@@

## Description

Extracts the @unidoc[Materializer] from the @unidoc[RequestContext], which can be useful when you want to run an
Akka Stream directly in your route.

See also @ref[withMaterializer](withMaterializer.md) to see how to customise the used materializer for specific inner routes.

## Example

Scala
:  @@snip [BasicDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #extractMaterializer-0 }

Java
:  @@snip [BasicDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #extractMaterializer }

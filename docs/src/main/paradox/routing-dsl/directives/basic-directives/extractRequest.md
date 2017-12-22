# extractRequest

@@@ div { .group-scala }

## Signature

@@signature [BasicDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #extractRequest }

@@@

## Description

Extracts the complete @unidoc[HttpRequest] instance.

Use `extractRequest` to extract just the complete URI of the request. Usually there's little use of
extracting the complete request because extracting of most of the aspects of HttpRequests is handled by specialized
directives. See @ref[Request Directives](../by-trait.md#request-directives).

## Example

Scala
:  @@snip [BasicDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #extractRequest-example }

Java
:  @@snip [BasicDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #extractRequest }

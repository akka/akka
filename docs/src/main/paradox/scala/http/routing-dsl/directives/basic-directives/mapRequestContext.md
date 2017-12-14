# mapRequestContext

@@@ div { .group-scala }

## Signature

@@signature [BasicDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #mapRequestContext }

@@@

## Description

Transforms the @unidoc[RequestContext] before it is passed to the inner route.

The `mapRequestContext` directive is used as a building block for @ref[Custom Directives](../custom-directives.md) to transform
the request context before it is passed to the inner route. To change only the request value itself the
@ref[mapRequest](mapRequest.md) directive can be used instead.

See @ref[Request Transforming Directives](index.md#request-transforming-directives) for an overview of similar directives.

## Example

Scala
:  @@snip [BasicDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #mapRequestContext }

Java
:  @@snip [BasicDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #mapRequestContext }

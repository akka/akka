# mapRouteResultFuture

@@@ div { .group-scala }

## Signature

@@signature [BasicDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #mapRouteResultFuture }

@@@

## Description

Asynchronous version of @ref[mapRouteResult](mapRouteResult.md).

It's similar to @ref[mapRouteResultWith](mapRouteResultWith.md), however it's
`Function<CompletionStage<RouteResult>, CompletionStage<RouteResult>>`
instead of `Function<RouteResult, CompletionStage<RouteResult>>` which may be useful when
combining multiple transformations and / or wanting to `recover` from a failed route result.

See @ref[Result Transformation Directives](index.md#result-transformation-directives-java) for similar directives.

## Example

Scala
:  @@snip [BasicDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #mapRouteResultFuture }

Java
:  @@snip [BasicDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #mapRouteResultFuture }

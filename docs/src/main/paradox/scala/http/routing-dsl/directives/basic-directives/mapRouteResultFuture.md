<a id="maprouteresultfuture"></a>
# mapRouteResultFuture

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #mapRouteResultFuture }

## Description

Asynchronous version of @ref[mapRouteResult](mapRouteResult.md#maprouteresult).

It's similar to @ref[mapRouteResultWith](mapRouteResultWith.md#maprouteresultwith), however it's `Future[RouteResult] ⇒ Future[RouteResult]`
instead of `RouteResult ⇒ Future[RouteResult]` which may be useful when combining multiple transformations
and / or wanting to `recover` from a failed route result.

See @ref[Result Transformation Directives](index.md#result-transformation-directives) for similar directives.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #mapRouteResultFuture }
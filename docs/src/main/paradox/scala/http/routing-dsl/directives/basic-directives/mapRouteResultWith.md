<a id="maprouteresultwith"></a>
# mapRouteResultWith

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #mapRouteResultWith }

## Description

Changes the message the inner route sends to the responder.

The `mapRouteResult` directive is used as a building block for @ref[Custom Directives](../custom-directives.md#custom-directives) to transform the
@ref[RouteResult](../../routes.md#routeresult) coming back from the inner route. It's similar to the @ref[mapRouteResult](mapRouteResult.md#maprouteresult) directive but
returning a `Future` instead of a result immediately, which may be useful for longer running transformations.

See @ref[Result Transformation Directives](index.md#result-transformation-directives) for similar directives.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #mapRouteResultWith-0 }
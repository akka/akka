<a id="maprouteresult"></a>
# mapRouteResult

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #mapRouteResult }

## Description

Changes the message the inner route sends to the responder.

The `mapRouteResult` directive is used as a building block for @ref[Custom Directives](../custom-directives.md#custom-directives) to transform the
@ref[RouteResult](../../routes.md#routeresult) coming back from the inner route.

See @ref[Result Transformation Directives](index.md#result-transformation-directives) for similar directives.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #mapRouteResult0 }
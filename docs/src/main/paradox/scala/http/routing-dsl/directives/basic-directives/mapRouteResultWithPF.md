<a id="maprouteresultwithpf"></a>
# mapRouteResultWithPF

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #mapRouteResultWithPF }

## Description

Asynchronous variant of @ref[mapRouteResultPF](mapRouteResultPF.md#maprouteresultpf).

Changes the message the inner route sends to the responder.

The `mapRouteResult` directive is used as a building block for @ref[Custom Directives](../custom-directives.md#custom-directives) to transform the
@ref[RouteResult](../../routes.md#routeresult) coming back from the inner route.

See @ref[Result Transformation Directives](index.md#result-transformation-directives) for similar directives.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #mapRouteResultWithPF-0 }
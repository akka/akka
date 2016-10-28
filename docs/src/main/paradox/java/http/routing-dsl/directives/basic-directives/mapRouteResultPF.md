<a id="maprouteresultpf-java"></a>
# mapRouteResultPF

## Description

*Partial Function* version of @ref[mapRouteResult](mapRouteResult.md#maprouteresult-java).

Changes the message the inner route sends to the responder.

The `mapRouteResult` directive is used as a building block for @ref[Custom Directives](../custom-directives.md#custom-directives-java) to transform the
`RouteResult` coming back from the inner route. It's similar to the @ref[mapRouteResult](mapRouteResult.md#maprouteresult-java) directive but allows to
specify a partial function that doesn't have to handle all potential `RouteResult` instances.

See @ref[Result Transformation Directives](index.md#result-transformation-directives-java) for similar directives.

## Example

@@snip [BasicDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #mapRouteResultPF }
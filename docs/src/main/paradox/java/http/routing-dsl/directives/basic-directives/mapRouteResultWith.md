<a id="maprouteresultwith-java"></a>
# mapRouteResultWith

## Description

Changes the message the inner route sends to the responder.

The `mapRouteResult` directive is used as a building block for @ref[Custom Directives](../custom-directives.md#custom-directives-java) to transform the
`RouteResult` coming back from the inner route. It's similar to the @ref[mapRouteResult](mapRouteResult.md#maprouteresult-java) directive but
returning a `CompletionStage` instead of a result immediately, which may be useful for longer running transformations.

See @ref[Result Transformation Directives](index.md#result-transformation-directives-java) for similar directives.

## Example

@@snip [BasicDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #mapRouteResultWith }
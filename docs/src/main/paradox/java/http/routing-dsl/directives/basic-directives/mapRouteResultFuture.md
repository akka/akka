<a id="maprouteresultfuture-java"></a>
# mapRouteResultFuture

## Description

Asynchronous version of @ref[mapRouteResult](mapRouteResult.md#maprouteresult-java).

It's similar to @ref[mapRouteResultWith](mapRouteResultWith.md#maprouteresultwith-java), however it's
`Function<CompletionStage<RouteResult>, CompletionStage<RouteResult>>`
instead of `Function<RouteResult, CompletionStage<RouteResult>>` which may be useful when
combining multiple transformations and / or wanting to `recover` from a failed route result.

See @ref[Result Transformation Directives](index.md#result-transformation-directives-java) for similar directives.

## Example

@@snip [BasicDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #mapRouteResultFuture }
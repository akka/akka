<a id="extractlog-java"></a>
# extractLog

## Description

Extracts a `LoggingAdapter` from the request context which can be used for logging inside the route.

The `extractLog` directive is used for providing logging to routes, such that they don't have to depend on
closing over a logger provided in the class body.

See @ref[extract](extract.md#extract-java) and @ref[Providing Values to Inner Routes](index.md#providedirectives-java) for an overview of similar directives.

## Example

@@snip [BasicDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #extractLog }
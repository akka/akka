<a id="reject-java"></a>
# reject

## Description

Explicitly rejects the request optionally using the given rejection(s).

`reject` uses the given rejection instances (which might be the empty `Seq`) to construct a `Route` which simply
calls `requestContext.reject`. See the chapter on @ref[Rejections](../../rejections.md#rejections-java) for more information on what this means.

After the request has been rejected at the respective point it will continue to flow through the routing structure in
the search for a route that is able to complete it.

The explicit `reject` directive is used mostly when building @ref[Custom Directives](../custom-directives.md#custom-directives), e.g. inside of a `flatMap`
modifier for "filtering out" certain cases.

## Example

@@snip [RouteDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/RouteDirectivesExamplesTest.java) { #reject }
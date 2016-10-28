<a id="maprequestcontext-java"></a>
# mapRequestContext

## Description

Transforms the `RequestContext` before it is passed to the inner route.

The `mapRequestContext` directive is used as a building block for @ref[Custom Directives](../custom-directives.md#custom-directives-java) to transform
the request context before it is passed to the inner route. To change only the request value itself the
@ref[mapRequest](mapRequest.md#maprequest-java) directive can be used instead.

See @ref[Request Transforming Directives](index.md#request-transforming-directives-java) for an overview of similar directives.

## Example

@@snip [BasicDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #mapRequestContext }
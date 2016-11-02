<a id="maprequestcontext"></a>
# mapRequestContext

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #mapRequestContext }

## Description

Transforms the `RequestContext` before it is passed to the inner route.

The `mapRequestContext` directive is used as a building block for @ref[Custom Directives](../custom-directives.md#custom-directives) to transform
the request context before it is passed to the inner route. To change only the request value itself the
@ref[mapRequest](mapRequest.md#maprequest) directive can be used instead.

See @ref[Request Transforming Directives](index.md#request-transforming-directives) for an overview of similar directives.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #mapRequestContext }
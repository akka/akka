<a id="extractrequestcontext"></a>
# extractRequestContext

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #extractRequestContext }

## Description

Extracts the request's underlying `RequestContext`.

This directive is used as a building block for most of the other directives,
which extract the context and by inspecting some of it's values can decide
what to do with the request - for example provide a value, or reject the request.

See also @ref[extractRequest](extractRequest.md#extractrequest) if only interested in the `HttpRequest` instance itself.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #extractRequestContext-example }
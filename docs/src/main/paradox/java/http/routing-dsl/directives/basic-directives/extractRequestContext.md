<a id="extractrequestcontext-java"></a>
# extractRequestContext

## Description

Extracts the request's underlying `RequestContext`.

This directive is used as a building block for most of the other directives,
which extract the context and by inspecting some of it's values can decide
what to do with the request - for example provide a value, or reject the request.

See also @ref[extractRequest](extractRequest.md#extractrequest-java) if only interested in the `HttpRequest` instance itself.

## Example

@@snip [BasicDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #extractRequestContext }
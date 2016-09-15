<a id="patch-java"></a>
# patch

Matches requests with HTTP method `PATCH`.

## Description

This directive filters the incoming request by its HTTP method. Only requests with
method `PATCH` are passed on to the inner route. All others are rejected with a
`MethodRejection`, which is translated into a `405 Method Not Allowed` response
by the default `RejectionHandler`.

## Example

@@snip [MethodDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/MethodDirectivesExamplesTest.java) { #patch }
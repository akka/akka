<a id="redirect-java"></a>
# redirect

## Description

Completes the request with a redirection response to a given target URI and of a given redirection type (status code).

`redirect` is a convenience helper for completing the request with a redirection response.
It is equivalent to this snippet relying on the `complete` directive:

## Example

@@snip [RouteDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/RouteDirectivesExamplesTest.java) { #redirect }
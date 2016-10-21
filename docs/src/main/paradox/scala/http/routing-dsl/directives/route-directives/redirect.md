<a id="redirect"></a>
# redirect

## Signature

@@signature [RouteDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/RouteDirectives.scala) { #redirect }

## Description

Completes the request with a redirection response to a given target URI and of a given redirection type (status code).

`redirect` is a convenience helper for completing the request with a redirection response.
It is equivalent to this snippet relying on the `complete` method on `RequestContext` (a directive is also available):

@@snip [RequestContextImpl.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/RequestContextImpl.scala) { #red-impl }

## Example

@@snip [RouteDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/RouteDirectivesExamplesSpec.scala) { #redirect-examples }
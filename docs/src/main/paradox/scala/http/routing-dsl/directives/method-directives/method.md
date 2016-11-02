<a id="method"></a>
# method

## Signature

@@signature [MethodDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/MethodDirectives.scala) { #method }

## Description

Matches HTTP requests based on their method.

This directive filters the incoming request by its HTTP method. Only requests with
the specified method are passed on to the inner route. All others are rejected with a
`MethodRejection`, which is translated into a `405 Method Not Allowed` response
by the default @ref[RejectionHandler](../../rejections.md#the-rejectionhandler).

## Example

@@snip [MethodDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/MethodDirectivesExamplesSpec.scala) { #method-example }
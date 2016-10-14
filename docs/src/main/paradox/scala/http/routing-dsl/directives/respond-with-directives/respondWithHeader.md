<a id="respondwithheader"></a>
# respondWithHeader

## Signature

@@signature [RespondWithDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/RespondWithDirectives.scala) { #respondWithHeader }

## Description

Adds a given HTTP header to all responses coming back from its inner route.

This directive transforms `HttpResponse` and `ChunkedResponseStart` messages coming back from its inner route by
adding the given `HttpHeader` instance to the headers list.

See also @ref[respondWithHeaders](respondWithHeaders.md#respondwithheaders) if you'd like to add more than one header.

## Example

@@snip [RespondWithDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/RespondWithDirectivesExamplesSpec.scala) { #respondWithHeader-0 }
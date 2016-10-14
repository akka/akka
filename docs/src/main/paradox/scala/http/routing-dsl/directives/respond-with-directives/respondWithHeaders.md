<a id="respondwithheaders"></a>
# respondWithHeaders

## Signature

@@signature [RespondWithDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/RespondWithDirectives.scala) { #respondWithHeaders }

## Description

Adds the given HTTP headers to all responses coming back from its inner route.

This directive transforms `HttpResponse` and `ChunkedResponseStart` messages coming back from its inner route by
adding the given `HttpHeader` instances to the headers list.

See also @ref[respondWithHeader](respondWithHeader.md#respondwithheader) if you'd like to add just a single header.

## Example

@@snip [RespondWithDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/RespondWithDirectivesExamplesSpec.scala) { #respondWithHeaders-0 }
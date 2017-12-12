# respondWithHeaders

## Description

Adds the given HTTP headers to all responses coming back from its inner route.

This directive transforms @unidoc[HttpResponse] and `ChunkedResponseStart` messages coming back from its inner route by
adding the given @unidoc[HttpHeader] instances to the headers list.

See also @ref[respondWithHeader](respondWithHeader.md) if you'd like to add just a single header.

## Example

@@snip [RespondWithDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/RespondWithDirectivesExamplesTest.java) { #respondWithHeaders }
<a id="respondwithheader-java"></a>
# respondWithHeader

## Description

Adds a given HTTP header to all responses coming back from its inner route.

This directive transforms `HttpResponse` and `ChunkedResponseStart` messages coming back from its inner route by
adding the given `HttpHeader` instance to the headers list.

See also @ref[respondWithHeaders](respondWithHeaders.md#respondwithheaders-java) if you'd like to add more than one header.

## Example

@@snip [RespondWithDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/RespondWithDirectivesExamplesTest.java) { #respondWithHeader }
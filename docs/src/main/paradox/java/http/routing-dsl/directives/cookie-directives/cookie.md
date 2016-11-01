<a id="cookie-java"></a>
# cookie

## Description

Extracts a cookie with a given name from a request or otherwise rejects the request with a `MissingCookieRejection` if
the cookie is missing.

Use the @ref[optionalCookie](optionalCookie.md#optionalcookie-java) directive instead if you want to support missing cookies in your inner route.

## Example

@@snip [CookieDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/CookieDirectivesExamplesTest.java) { #cookie }
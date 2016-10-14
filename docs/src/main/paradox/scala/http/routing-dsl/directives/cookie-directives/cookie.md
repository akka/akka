<a id="cookie"></a>
# cookie

## Signature

@@signature [CookieDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/CookieDirectives.scala) { #cookie }

## Description

Extracts a cookie with a given name from a request or otherwise rejects the request with a `MissingCookieRejection` if
the cookie is missing.

Use the @ref[optionalCookie](optionalCookie.md#optionalcookie) directive instead if you want to support missing cookies in your inner route.

## Example

@@snip [CookieDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/CookieDirectivesExamplesSpec.scala) { #cookie }
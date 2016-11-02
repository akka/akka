<a id="deletecookie"></a>
# deleteCookie

## Signature

@@signature [CookieDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/CookieDirectives.scala) { #deleteCookie }

## Description

Adds a header to the response to request the removal of the cookie with the given name on the client.

Use the @ref[setCookie](setCookie.md#setcookie) directive to update a cookie.

## Example

@@snip [CookieDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/CookieDirectivesExamplesSpec.scala) { #deleteCookie }
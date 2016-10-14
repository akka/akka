<a id="setcookie"></a>
# setCookie

## Signature

@@signature [CookieDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/CookieDirectives.scala) { #setCookie }

## Description

Adds a header to the response to request the update of the cookie with the given name on the client.

Use the @ref[deleteCookie](deleteCookie.md#deletecookie) directive to delete a cookie.

## Example

@@snip [CookieDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/CookieDirectivesExamplesSpec.scala) { #setCookie }
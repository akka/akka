# deleteCookie

## Description

Adds a header to the response to request the removal of the cookie with the given name on the client.

Use the @ref[setCookie](setCookie.md) directive to update a cookie.

## Example

Scala
:  @@snip [CookieDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/CookieDirectivesExamplesSpec.scala) { #deleteCookie }

Java
:  @@snip [CookieDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/CookieDirectivesExamplesTest.java) { #deleteCookie }

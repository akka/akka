# cookie

@@@ div { .group-scala }

## Signature

@@signature [CookieDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/CookieDirectives.scala) { #cookie }

@@@

## Description

Extracts a cookie with a given name from a request or otherwise rejects the request with a @unidoc[MissingCookieRejection] if
the cookie is missing.

Use the @ref[optionalCookie](optionalCookie.md) directive instead if you want to support missing cookies in your inner route.

## Example

Scala
:  @@snip [CookieDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/CookieDirectivesExamplesSpec.scala) { #cookie }

Java
:  @@snip [CookieDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/CookieDirectivesExamplesTest.java) { #cookie }

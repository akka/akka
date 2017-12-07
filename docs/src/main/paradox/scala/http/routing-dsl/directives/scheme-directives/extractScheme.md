# extractScheme

@@@ div { .group-scala }

## Signature

@@signature [SchemeDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/SchemeDirectives.scala) { #extractScheme }

@@@

## Description

Extracts the Uri scheme (i.e. "`http`", "`https`", etc.) for an incoming request.

For rejecting a request if it doesn't match a specified scheme name, see the @ref[scheme](scheme.md) directive.

## Example

Scala
:  @@snip [SchemeDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/SchemeDirectivesExamplesSpec.scala) { #example-1 }

Java
:  @@snip [SchemeDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/SchemeDirectivesExamplesTest.java) { #extractScheme }

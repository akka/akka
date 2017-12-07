# scheme

@@@ div { .group-scala }

## Signature

@@signature [SchemeDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/SchemeDirectives.scala) { #scheme }

@@@

## Description

Rejects a request if its Uri scheme does not match a given one.

The `scheme` directive can be used to match requests by their Uri scheme, only passing
through requests that match the specified scheme and rejecting all others.

A typical use case for the `scheme` directive would be to reject requests coming in over
http instead of https, or to redirect such requests to the matching https URI with a
`MovedPermanently`.

For simply extracting the scheme name, see the @ref[extractScheme](extractScheme.md) directive.

## Example

Scala
:  @@snip [SchemeDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/SchemeDirectivesExamplesSpec.scala) { #example-2 }

Java
:  @@snip [SchemeDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/SchemeDirectivesExamplesTest.java) { #scheme }

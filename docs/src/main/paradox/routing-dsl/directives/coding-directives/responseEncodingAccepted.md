# responseEncodingAccepted

@@@ div { .group-scala }

## Signature

@@signature [CodingDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/CodingDirectives.scala) { #responseEncodingAccepted }

@@@

## Description

Passes the request to the inner route if the request accepts the argument encoding. Otherwise, rejects the request with an `UnacceptedResponseEncodingRejection(encoding)`.

## Example

Scala
:  @@snip [CodingDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/CodingDirectivesExamplesSpec.scala) { #responseEncodingAccepted }

Java
:  @@snip [CodingDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/CodingDirectivesExamplesTest.java) { #responseEncodingAccepted }

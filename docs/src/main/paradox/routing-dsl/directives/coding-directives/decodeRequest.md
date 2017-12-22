# decodeRequest

@@@ div { .group-scala }

## Signature

@@signature [CodingDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/CodingDirectives.scala) { #decodeRequest }

@@@

## Description

Decompresses the incoming request if it is `gzip` or `deflate` compressed. Uncompressed requests are passed through untouched. If the request encoded with another encoding the request is rejected with an @unidoc[UnsupportedRequestEncodingRejection].

## Example

Scala
:  @@snip [CodingDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/CodingDirectivesExamplesSpec.scala) { #decodeRequest }

Java
:  @@snip [CodingDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/CodingDirectivesExamplesTest.java) { #decodeRequest }

<a id="decoderequest"></a>
# decodeRequest

## Signature

@@signature [CodingDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/CodingDirectives.scala) { #decodeRequest }

## Description

Decompresses the incoming request if it is `gzip` or `deflate` compressed. Uncompressed requests are passed through untouched. If the request encoded with another encoding the request is rejected with an `UnsupportedRequestEncodingRejection`.

## Example

@@snip [CodingDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/CodingDirectivesExamplesSpec.scala) { #"decodeRequest" }
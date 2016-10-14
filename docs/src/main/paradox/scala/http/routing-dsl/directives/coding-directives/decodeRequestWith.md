<a id="decoderequestwith"></a>
# decodeRequestWith

## Signature

@@signature [CodingDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/CodingDirectives.scala) { #decodeRequestWith }

## Description

Decodes the incoming request if it is encoded with one of the given encoders. If the request encoding doesn't match one of the given encoders the request is rejected with an `UnsupportedRequestEncodingRejection`. If no decoders are given the default encoders (`Gzip`, `Deflate`, `NoCoding`) are used.

## Example

@@snip [CodingDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/CodingDirectivesExamplesSpec.scala) { #decodeRequestWith }
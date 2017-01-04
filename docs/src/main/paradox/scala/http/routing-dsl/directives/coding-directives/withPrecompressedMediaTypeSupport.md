# withPrecompressedMediaTypeSupport

## Signature

@@signature [CodingDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/CodingDirectives.scala) { #withPrecompressedMediaTypeSupport }

## Description

Inspects the response entity and adds a `Content-Encoding: gzip` response header if
the entity's media-type is precompressed with gzip and no `Content-Encoding` header is present yet.

## Example

@@snip [CodingDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/CodingDirectivesExamplesSpec.scala) { #withPrecompressedMediaTypeSupport }

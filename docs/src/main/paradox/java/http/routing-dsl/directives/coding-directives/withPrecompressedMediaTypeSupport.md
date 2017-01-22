# withPrecompressedMediaTypeSupport

## Description

Inspects the response entity and adds a `Content-Encoding: gzip` response header if
the entity's media-type is precompressed with gzip and no `Content-Encoding` header is present yet.

## Example

@@snip [CodingDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/CodingDirectivesExamplesTest.java) { #withPrecompressedMediaTypeSupport }

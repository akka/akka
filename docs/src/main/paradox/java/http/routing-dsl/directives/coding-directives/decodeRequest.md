<a id="decoderequest-java"></a>
# decodeRequest

## Description

Decompresses the incoming request if it is `gzip` or `deflate` compressed. Uncompressed requests are passed through untouched. If the request encoded with another encoding the request is rejected with an `UnsupportedRequestEncodingRejection`.

## Example

@@snip [CodingDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/CodingDirectivesExamplesTest.java) { #decodeRequest }
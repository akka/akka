# extractDataBytes

## Description

Extracts the entities data bytes as @unidoc[Source[ByteString, NotUsed]] from the @unidoc[RequestContext].

The directive returns a stream containing the request data bytes.

## Example

@@snip [BasicDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #extractDataBytes }
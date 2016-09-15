<a id="extractdatabytes-java"></a>
# extractDataBytes

## Description

Extracts the entities data bytes as `Source[ByteString, Any]` from the `RequestContext`.

The directive returns a stream containing the request data bytes.

## Example

@@snip [BasicDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #extractDataBytes }
<a id="extractdatabytes"></a>
# extractDataBytes

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #extractDataBytes }

## Description

Extracts the entities data bytes as `Source[ByteString, Any]` from the `RequestContext`.

The directive returns a stream containing the request data bytes.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #extractDataBytes-example }
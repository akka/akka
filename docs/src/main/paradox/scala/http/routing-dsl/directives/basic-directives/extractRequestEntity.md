<a id="extractrequestentity"></a>
# extractRequestEntity

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #extractRequestEntity }

## Description

Extracts the `RequestEntity` from the `RequestContext`.

The directive returns a `RequestEntity` without unmarshalling the request. To extract domain entity,
@ref[entity](../marshalling-directives/entity.md#entity) should be used.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #extractRequestEntity-example }
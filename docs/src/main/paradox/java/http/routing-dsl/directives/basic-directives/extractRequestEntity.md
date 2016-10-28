<a id="extractrequestentity-java"></a>
# extractRequestEntity

## Description

Extracts the `RequestEntity` from the `RequestContext`.

The directive returns a `RequestEntity` without unmarshalling the request. To extract domain entity,
@ref[entity](../marshalling-directives/entity.md#entity-java) should be used.

## Example

@@snip [BasicDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #extractRequestEntity }
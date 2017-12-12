# extractRequestEntity

## Description

Extracts the @unidoc[RequestEntity] from the @unidoc[RequestContext].

The directive returns a @unidoc[RequestEntity] without unmarshalling the request. To extract domain entity,
@ref[entity](../marshalling-directives/entity.md) should be used.

## Example

@@snip [BasicDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #extractRequestEntity }
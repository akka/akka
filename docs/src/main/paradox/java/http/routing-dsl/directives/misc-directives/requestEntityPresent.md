<a id="requestentitypresent-java"></a>
# requestEntityPresent

## Description

A simple filter that checks if the request entity is present and only then passes processing to the inner route.
Otherwise, the request is rejected with `RequestEntityExpectedRejection`.

See also @ref[requestEntityEmpty](requestEntityEmpty.md#requestentityempty-java) for the opposite effect.

## Example

@@snip [MiscDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/MiscDirectivesExamplesTest.java) { #requestEntity-empty-present-example }
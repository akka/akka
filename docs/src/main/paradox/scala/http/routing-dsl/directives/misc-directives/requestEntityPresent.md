<a id="requestentitypresent"></a>
# requestEntityPresent

## Signature

@@signature [MiscDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/MiscDirectives.scala) { #requestEntityPresent }

## Description

A simple filter that checks if the request entity is present and only then passes processing to the inner route.
Otherwise, the request is rejected with `RequestEntityExpectedRejection`.

See also @ref[requestEntityEmpty](requestEntityEmpty.md#requestentityempty) for the opposite effect.

## Example

@@snip [MiscDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/MiscDirectivesExamplesSpec.scala) { #requestEntityEmptyPresent-example }
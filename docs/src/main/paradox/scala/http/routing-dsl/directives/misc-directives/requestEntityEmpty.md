<a id="requestentityempty"></a>
# requestEntityEmpty

## Signature

@@signature [MiscDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/MiscDirectives.scala) { #requestEntityEmpty }

## Description

A filter that checks if the request entity is empty and only then passes processing to the inner route.
Otherwise, the request is rejected.

See also @ref[requestEntityPresent](requestEntityPresent.md#requestentitypresent) for the opposite effect.

## Example

@@snip [MiscDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/MiscDirectivesExamplesSpec.scala) { #requestEntityEmptyPresent-example }
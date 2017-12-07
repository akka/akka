# requestEntityEmpty

## Description

A filter that checks if the request entity is empty and only then passes processing to the inner route.
Otherwise, the request is rejected.

See also @ref[requestEntityPresent](requestEntityPresent.md) for the opposite effect.

## Example

Scala
:  @@snip [MiscDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/MiscDirectivesExamplesSpec.scala) { #requestEntityEmptyPresent-example }

Java
:  @@snip [MiscDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/MiscDirectivesExamplesTest.java) { #requestEntity-empty-present-example }

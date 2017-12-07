# cancelRejections

@@@ div { .group-scala }

## Signature

@@signature [BasicDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #cancelRejections }

@@@

## Description

Adds a @unidoc[TransformationRejection] cancelling all rejections created by the inner route for which
the condition argument function returns `true`.

See also @ref[cancelRejection](cancelRejection.md), for canceling a specific rejection.

Read @ref[Rejections](../../rejections.md) to learn more about rejections.

For more advanced handling of rejections refer to the @ref[handleRejections](../execution-directives/handleRejections.md) directive
which provides a nicer DSL for building rejection handlers.

## Example

Scala
:  @@snip [BasicDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #cancelRejections-filter-example }

Java
:  @@snip [BasicDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #cancelRejections }

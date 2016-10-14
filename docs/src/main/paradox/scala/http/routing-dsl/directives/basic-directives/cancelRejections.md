<a id="cancelrejections"></a>
# cancelRejections

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #cancelRejections }

## Description

Adds a `TransformationRejection` cancelling all rejections created by the inner route for which
the condition argument function returns `true`.

See also @ref[cancelRejection](cancelRejection.md#cancelrejection), for canceling a specific rejection.

Read @ref[Rejections](../../rejections.md#rejections-scala) to learn more about rejections.

For more advanced handling of rejections refer to the @ref[handleRejections](../execution-directives/handleRejections.md#handlerejections) directive
which provides a nicer DSL for building rejection handlers.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #cancelRejections-filter-example }
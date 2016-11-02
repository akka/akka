<a id="cancelrejection"></a>
# cancelRejection

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #cancelRejection }

## Description

Adds a `TransformationRejection` cancelling all rejections equal to the
given one to the rejections potentially coming back from the inner route.

Read @ref[Rejections](../../rejections.md#rejections-scala) to learn more about rejections.

For more advanced handling of rejections refer to the @ref[handleRejections](../execution-directives/handleRejections.md#handlerejections) directive
which provides a nicer DSL for building rejection handlers.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #cancelRejection-example }
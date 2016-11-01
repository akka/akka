<a id="cancelrejection-java"></a>
# cancelRejection

## Description

Adds a `TransformationRejection` cancelling all rejections equal to the
given one to the rejections potentially coming back from the inner route.

Read @ref[Rejections](../../rejections.md#rejections-java) to learn more about rejections.

For more advanced handling of rejections refer to the @ref[handleRejections](../execution-directives/handleRejections.md#handlerejections-java) directive
which provides a nicer DSL for building rejection handlers.

## Example

@@snip [BasicDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #cancelRejection }
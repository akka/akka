<a id="cancelrejections-java"></a>
# cancelRejections

## Description

Adds a `TransformationRejection` cancelling all rejections created by the inner route for which
the condition argument function returns `true`.

See also @ref[cancelRejection](cancelRejection.md#cancelrejection-java), for canceling a specific rejection.

Read @ref[Rejections](../../rejections.md#rejections-java) to learn more about rejections.

For more advanced handling of rejections refer to the @ref[handleRejections](../execution-directives/handleRejections.md#handlerejections-java) directive
which provides a nicer DSL for building rejection handlers.

## Example

@@snip [BasicDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #cancelRejections }
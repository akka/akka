<a id="maprejections-java"></a>
# mapRejections

## Description

**Low level directive** – unless you're sure you need to be working on this low-level you might instead
want to try the @ref[handleRejections](../execution-directives/handleRejections.md#handlerejections-java) directive which provides a nicer DSL for building rejection handlers.

The `mapRejections` directive is used as a building block for @ref[Custom Directives](../custom-directives.md#custom-directives-java) to transform a list
of rejections from the inner route to a new list of rejections.

See @ref[Response Transforming Directives](index.md#response-transforming-directives-java) for similar directives.

## Example

@@snip [BasicDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #mapRejections }
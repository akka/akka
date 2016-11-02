<a id="extract-java"></a>
# extract

## Description

The `extract` directive is used as a building block for @ref[Custom Directives](../custom-directives.md#custom-directives-java) to extract data from the
`RequestContext` and provide it to the inner route.

See @ref[Providing Values to Inner Routes](index.md#providedirectives-java) for an overview of similar directives.

## Example

@@snip [BasicDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #extract }
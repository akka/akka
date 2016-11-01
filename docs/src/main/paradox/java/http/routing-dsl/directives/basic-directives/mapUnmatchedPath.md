<a id="mapunmatchedpath-java"></a>
# mapUnmatchedPath

## Description

Transforms the unmatchedPath field of the request context for inner routes.

The `mapUnmatchedPath` directive is used as a building block for writing @ref[Custom Directives](../custom-directives.md#custom-directives-java). You can use it
for implementing custom path matching directives.

Use `extractUnmatchedPath` for extracting the current value of the unmatched path.

## Example

@@snip [BasicDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #mapUnmatchedPath }
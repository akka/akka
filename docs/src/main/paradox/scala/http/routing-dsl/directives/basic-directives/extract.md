<a id="extract"></a>
# extract

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #extract }

## Description

The `extract` directive is used as a building block for @ref[Custom Directives](../custom-directives.md#custom-directives) to extract data from the
`RequestContext` and provide it to the inner route. It is a special case for extracting one value of the more
general @ref[textract](textract.md#textract) directive that can be used to extract more than one value.

See @ref[Providing Values to Inner Routes](index.md#providedirectives) for an overview of similar directives.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #extract0 }
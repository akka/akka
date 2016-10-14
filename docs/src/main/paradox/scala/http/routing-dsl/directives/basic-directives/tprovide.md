<a id="tprovide"></a>
# tprovide

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #tprovide }

## Description

Provides a tuple of values to the inner route.

The `tprovide` directive is used as a building block for @ref[Custom Directives](../custom-directives.md#custom-directives) to provide data to the inner route.
To provide just one value use the @ref[provide](provide.md#provide) directive. If you want to provide values calculated from the
`RequestContext` use the @ref[textract](textract.md#textract) directive instead.

See @ref[Providing Values to Inner Routes](index.md#providedirectives) for an overview of similar directives.

See also @ref[provide](provide.md#provide) for providing a single value.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #tprovide }
@@@ div { .group-java }

The `tprovide` directive is not available in the Java API.

@@@

@@@ div { .group-scala }
# tprovide

## Signature

@@signature [BasicDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #tprovide }

## Description

Provides a tuple of values to the inner route.

The `tprovide` directive is used as a building block for @ref[Custom Directives](../custom-directives.md) to provide data to the inner route.
To provide just one value use the @ref[provide](provide.md) directive. If you want to provide values calculated from the
@unidoc[RequestContext] use the @ref[textract](textract.md) directive instead.

See @ref[Providing Values to Inner Routes](index.md#providedirectives) for an overview of similar directives.

See also @ref[provide](provide.md) for providing a single value.

## Example

@@snip [BasicDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #tprovide }

@@@

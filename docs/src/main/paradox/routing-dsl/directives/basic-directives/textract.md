@@@ div { .group-java }

The `textract` directive is not available in the Java API.

@@@

@@@ div { .group-scala }

# textract

## Signature

@@signature [BasicDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #textract }

## Description

Extracts a tuple of values from the request context and provides them to the inner route.

The `textract` directive is used as a building block for @ref[Custom Directives](../custom-directives.md) to extract data from the
@unidoc[RequestContext] and provide it to the inner route. To extract just one value use the @ref[extract](extract.md) directive. To
provide a constant value independent of the @unidoc[RequestContext] use the @ref[tprovide](tprovide.md) directive instead.

See @ref[Providing Values to Inner Routes](index.md#providedirectives) for an overview of similar directives.

See also @ref[extract](extract.md) for extracting a single value.

## Example

@@snip [BasicDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #textract }

@@@

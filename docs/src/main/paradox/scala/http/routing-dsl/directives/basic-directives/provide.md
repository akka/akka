<a id="provide"></a>
# provide

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #provide }

## Description

Provides a constant value to the inner route.

The *provide* directive is used as a building block for @ref[Custom Directives](../custom-directives.md#custom-directives) to provide a single value to the
inner route. To provide several values  use the @ref[tprovide](tprovide.md#tprovide) directive.

See @ref[Providing Values to Inner Routes](index.md#providedirectives) for an overview of similar directives.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #provide0 }
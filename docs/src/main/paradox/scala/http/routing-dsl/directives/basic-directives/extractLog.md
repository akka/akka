<a id="extractlog"></a>
# extractLog

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #extractLog }

## Description

Extracts a `LoggingAdapter` from the request context which can be used for logging inside the route.

The `extractLog` directive is used for providing logging to routes, such that they don't have to depend on
closing over a logger provided in the class body.

See @ref[extract](extract.md#extract) and @ref[Providing Values to Inner Routes](index.md#providedirectives) for an overview of similar directives.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #extract0Log }
<a id="extracturi"></a>
# extractUri

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #extractUri }

## Description

Access the full URI of the request.

Use @ref[SchemeDirectives](../scheme-directives/index.md#schemedirectives), @ref[HostDirectives](../host-directives/index.md#hostdirectives), @ref[PathDirectives](../path-directives/index.md#pathdirectives),  and @ref[ParameterDirectives](../parameter-directives/index.md#parameterdirectives) for more
targeted access to parts of the URI.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #extractUri-example }
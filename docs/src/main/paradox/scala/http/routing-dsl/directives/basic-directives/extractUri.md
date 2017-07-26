# extractUri

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #extractUri }

## Description

Access the full URI of the request.

Use @ref[SchemeDirectives](../scheme-directives/index.md), @ref[HostDirectives](../host-directives/index.md), @ref[PathDirectives](../path-directives/index.md),  and @ref[ParameterDirectives](../parameter-directives/index.md) for more
targeted access to parts of the URI.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #extractUri-example }
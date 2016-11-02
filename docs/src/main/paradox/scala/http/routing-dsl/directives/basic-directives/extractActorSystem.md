<a id="extractactorsystem"></a>
# extractActorSystem

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #extractActorSystem }

## Description

Extracts the `ActorSystem` from the `RequestContext`, which can be useful when the external API
in your route needs one.

@@@ warning
This is only supported when the available Materializer is an ActorMaterializer.
@@@

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #extractActorSystem-example }

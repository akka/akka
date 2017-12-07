# extractActorSystem

@@@ div { .group-scala }

## Signature

@@signature [BasicDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #extractActorSystem }

@@@

## Description

Extracts the @unidoc[ActorSystem] from the @unidoc[RequestContext], which can be useful when the external API
in your route needs one.

@@@ warning
This is only supported when the available Materializer is an ActorMaterializer.
@@@

## Example

Scala
:  @@snip [BasicDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #extractActorSystem-example }

Java
:  @@snip [BasicDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #extractActorSystem }

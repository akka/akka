# extractSettings

@@@ div { .group-scala }

## Signature

@@signature [BasicDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #extractSettings }

@@@

## Description

Extracts the @unidoc[RoutingSettings] from the @unidoc[RequestContext].

By default the settings of the `Http()` extension running the route will be returned.
It is possible to override the settings for specific sub-routes by using the @ref[withSettings](withSettings.md) directive.

## Example

Scala
:  @@snip [BasicDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #extractSettings-examples }

Java
:  @@snip [BasicDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #extractRequestContext }

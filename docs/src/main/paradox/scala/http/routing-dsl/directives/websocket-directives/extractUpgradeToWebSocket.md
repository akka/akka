# extractUpgradeToWebSocket

@@@ div { .group-scala }

## Signature

@@signature [WebSocketDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/WebSocketDirectives.scala) { #extractUpgradeToWebSocket }

@@@

## Description

Extracts the @unidoc[UpgradeToWebSocket] header if existent. Rejects with an @unidoc[ExpectedWebSocketRequestRejection], otherwise.

The `extractUpgradeToWebSocket` directive is used as a building block for @ref[Custom Directives](../custom-directives.md) to provide the extracted header to the inner route.

## Example

Scala
:  @@snip [WebSocketDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/WebSocketDirectivesExamplesSpec.scala) { #extractUpgradeToWebSocket }

Java
:  @@snip [WebSocketDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/WebSocketDirectivesExamplesTest.java) { #extractUpgradeToWebSocket }

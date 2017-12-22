# extractOfferedWsProtocols

@@@ div { .group-scala }

## Signature

@@signature [WebSocketDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/WebSocketDirectives.scala) { #extractOfferedWsProtocols }

@@@

## Description

Extracts the list of WebSocket subprotocols as offered by the client in the `Sec-WebSocket-Protocol` header if this is a WebSocket request. Rejects with an @unidoc[ExpectedWebSocketRequestRejection], otherwise.

The `extractOfferedWsProtocols` directive is used as a building block for @ref[Custom Directives](../custom-directives.md) to provide the extracted protocols to the inner route.

## Example

Scala
:  @@snip [WebSocketDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/WebSocketDirectivesExamplesSpec.scala) { #extractOfferedWsProtocols }

Java
:  @@snip [WebSocketDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/WebSocketDirectivesExamplesTest.java) { #extractOfferedWsProtocols }

# extractOfferedWsProtocols

## Signature

@@signature [WebSocketDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/WebSocketDirectives.scala) { #extractOfferedWsProtocols }

## Description

Extract the list of WebSocket subprotocols as offered by the client in the `Sec-WebSocket-Protocol` header if this is a WebSocket request. Rejects with an @scaladoc:[ExpectedWebSocketRequestRejection](akka.http.scaladsl.server.ExpectedWebSocketRequestRejection), otherwise.

The `extractOfferedWsProtocols` directive is used as a building block for @ref[Custom Directives](../custom-directives.md#custom-directives) to provide the extracted protocols to the inner route.

## Example

@@snip [WebSocketDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/WebSocketDirectivesExamplesSpec.scala) { #extractOfferedWsProtocols }

# extractOfferedWsProtocols

## Description

Extracts the list of WebSocket subprotocols as offered by the client in the `Sec-WebSocket-Protocol` header if this is a WebSocket request. Rejects with an @javadoc:[ExpectedWebSocketRequestRejection](akka.http.javadsl.server.ExpectedWebSocketRequestRejection), otherwise.

The `extractOfferedWsProtocols` directive is used as a building block for @ref[Custom Directives](../custom-directives.md) to provide the extracted protocols to the inner route.

## Example

@@snip [WebSocketDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/WebSocketDirectivesExamplesTest.java) { #extractOfferedWsProtocols }

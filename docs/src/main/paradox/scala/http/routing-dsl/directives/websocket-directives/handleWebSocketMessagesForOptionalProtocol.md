# handleWebSocketMessagesForOptionalProtocol

## Signature

@@signature [WebSocketDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/WebSocketDirectives.scala) { #handleWebSocketMessagesForOptionalProtocol }

## Description

Handles WebSocket requests with the given handler and rejects other requests with an
@scaladoc:[ExpectedWebSocketRequestRejection](akka.http.scaladsl.server.ExpectedWebSocketRequestRejection$).

If the `subprotocol` parameter is None any WebSocket request is accepted. If the `subprotocol` parameter is
`Some(protocol)` a WebSocket request is only accepted if the list of subprotocols supported by the client (as
announced in the WebSocket request) contains `protocol`. If the client did not offer the protocol in question
the request is rejected with an @scaladoc:[UnsupportedWebSocketSubprotocolRejection](akka.http.scaladsl.server.UnsupportedWebSocketSubprotocolRejection).

To support several subprotocols you may chain several `handleWebSocketMessagesForOptionalProtocol` routes.

The `handleWebSocketMessagesForOptionalProtocol` directive is used as a building block for @ref[WebSocket Directives](index.md) to handle websocket messages.

For more information about the WebSocket support, see @ref[Server-Side WebSocket Support](../../../websocket-support.md#server-side-websocket-support-scala).

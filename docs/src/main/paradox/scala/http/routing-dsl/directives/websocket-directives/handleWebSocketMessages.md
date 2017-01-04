<a id="handlewebsocketmessages"></a>
# handleWebSocketMessages

## Signature

@@signature [WebSocketDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/WebSocketDirectives.scala) { #handleWebSocketMessages }

## Description

The directive first checks if the request was a valid WebSocket handshake request and if yes, it completes the request
with the passed handler. Otherwise, the request is rejected with an `ExpectedWebSocketRequestRejection`.

WebSocket subprotocols offered in the `Sec-WebSocket-Protocol` header of the request are ignored. If you want to
support several protocols use the @ref[handleWebSocketMessagesForProtocol](handleWebSocketMessagesForProtocol.md#handlewebsocketmessagesforprotocol) directive, instead.

For more information about the WebSocket support, see @ref[Server-Side WebSocket Support](../../../websocket-support.md#server-side-websocket-support-scala).

## Example

@@snip [WebSocketDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/WebSocketDirectivesExamplesSpec.scala) { #greeter-service }

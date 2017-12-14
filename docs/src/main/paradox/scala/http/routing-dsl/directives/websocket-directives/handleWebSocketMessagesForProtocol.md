# handleWebSocketMessagesForProtocol

@@@ div { .group-scala }

## Signature

@@signature [WebSocketDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/WebSocketDirectives.scala) { #handleWebSocketMessagesForProtocol }

@@@

## Description

Handles WebSocket requests with the given handler if the given subprotocol is offered in the `Sec-WebSocket-Protocol`
header of the request and rejects other requests with an @unidoc[ExpectedWebSocketRequestRejection] or an
@unidoc[UnsupportedWebSocketSubprotocolRejection].

The directive first checks if the request was a valid WebSocket handshake request and if the request offers the passed
subprotocol name. If yes, the directive completes the request with the passed handler. Otherwise, the request is
either rejected with an @unidoc[ExpectedWebSocketRequestRejection] or an @unidoc[UnsupportedWebSocketSubprotocolRejection].

To support several subprotocols, for example at the same path, several instances of `handleWebSocketMessagesForProtocol` can
be chained using `~` as you can see in the below example.

For more information about the WebSocket support, see @ref[Server-Side WebSocket Support](../../../server-side/websocket-support.md).

## Example

Scala
:  @@snip [WebSocketDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/WebSocketDirectivesExamplesSpec.scala) { #handle-multiple-protocols }

Java
:  @@snip [WebSocketDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/WebSocketDirectivesExamplesTest.java) { #handleWebSocketMessagesForProtocol }

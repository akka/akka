.. _-handleWebSocketMessagesForProtocol-:

handleWebSocketMessagesForProtocol
==================================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/WebSocketDirectives.scala
   :snippet: handleWebSocketMessagesForProtocol

Description
-----------
Handles WebSocket requests with the given handler if the given subprotocol is offered in the ``Sec-WebSocket-Protocol``
header of the request and rejects other requests with an ``ExpectedWebSocketRequestRejection`` or an
``UnsupportedWebSocketSubprotocolRejection``.

The directive first checks if the request was a valid WebSocket handshake request and if the request offers the passed
subprotocol name. If yes, the directive completes the request with the passed handler. Otherwise, the request is
either rejected with an ``ExpectedWebSocketRequestRejection`` or an ``UnsupportedWebSocketSubprotocolRejection``.

To support several subprotocols, for example at the same path, several instances of ``handleWebSocketMessagesForProtocol`` can
be chained using ``~`` as you can see in the below example.

For more information about the WebSocket support, see :ref:`server-side-websocket-support-scala`.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/WebSocketDirectivesExamplesSpec.scala
   :snippet: handle-multiple-protocols

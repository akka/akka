.. _-handleWebSocketMessages-:

handleWebSocketMessages
=======================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/WebSocketDirectives.scala
   :snippet: handleWebSocketMessages

Description
-----------

The directive first checks if the request was a valid WebSocket handshake request and if yes, it completes the request
with the passed handler. Otherwise, the request is rejected with an ``ExpectedWebSocketRequestRejection``.

WebSocket subprotocols offered in the ``Sec-WebSocket-Protocol`` header of the request are ignored. If you want to
support several protocols use the :ref:`-handleWebSocketMessagesForProtocol-` directive, instead.

For more information about the WebSocket support, see :ref:`server-side-websocket-support-scala`.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/WebSocketDirectivesExamplesSpec.scala
   :snippet: greeter-service

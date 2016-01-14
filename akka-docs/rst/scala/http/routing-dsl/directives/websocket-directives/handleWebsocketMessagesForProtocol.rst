.. _-handleWebsocketMessagesForProtocol-:

handleWebsocketMessagesForProtocol
==================================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/WebsocketDirectives.scala
   :snippet: handleWebsocketMessagesForProtocol

Description
-----------
Handles Websocket requests with the given handler if the given subprotocol is offered in the ``Sec-Websocket-Protocol``
header of the request and rejects other requests with an ``ExpectedWebsocketRequestRejection`` or an
``UnsupportedWebsocketSubprotocolRejection``.

The directive first checks if the request was a valid Websocket handshake request and if the request offers the passed
subprotocol name. If yes, the directive completes the request with the passed handler. Otherwise, the request is
either rejected with an ``ExpectedWebsocketRequestRejection`` or an ``UnsupportedWebsocketSubprotocolRejection``.

To support several subprotocols, for example at the same path, several instances of ``handleWebsocketMessagesForProtocol`` can
be chained using ``~`` as you can see in the below example.

For more information about the Websocket support, see :ref:`server-side-websocket-support-scala`.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/WebsocketDirectivesExamplesSpec.scala
   :snippet: handle-multiple-protocols

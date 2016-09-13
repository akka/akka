.. _-handleWebSocketMessages-java-:

handleWebSocketMessages
=======================

Description
-----------

The directive first checks if the request was a valid WebSocket handshake request and if yes, it completes the request
with the passed handler. Otherwise, the request is rejected with an ``ExpectedWebSocketRequestRejection``.

WebSocket subprotocols offered in the ``Sec-WebSocket-Protocol`` header of the request are ignored. If you want to
support several protocols use the :ref:`-handleWebSocketMessagesForProtocol-java-` directive, instead.

For more information about the WebSocket support, see :ref:`server-side-websocket-support-java`.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/WebSocketDirectivesExamplesTest.java#handleWebSocketMessages

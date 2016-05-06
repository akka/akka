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
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.

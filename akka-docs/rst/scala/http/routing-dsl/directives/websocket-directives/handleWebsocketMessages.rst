.. _-handleWebsocketMessages-:

handleWebsocketMessages
=======================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/WebsocketDirectives.scala
   :snippet: handleWebsocketMessages

Description
-----------

The directive first checks if the request was a valid Websocket handshake request and if yes, it completes the request
with the passed handler. Otherwise, the request is rejected with an ``ExpectedWebsocketRequestRejection``.

Websocket subprotocols offered in the ``Sec-Websocket-Protocol`` header of the request are ignored. If you want to
support several protocols use the :ref:`-handleWebsocketMessagesForProtocol-` directive, instead.

For more information about the Websocket support, see :ref:`server-side-websocket-support-scala`.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/WebsocketDirectivesExamplesSpec.scala
   :snippet: greeter-service

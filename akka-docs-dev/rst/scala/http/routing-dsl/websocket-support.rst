.. _server-side-websocket-support-scala:

Server-Side WebSocket Support
=============================

WebSocket is a protocol that provides a bi-directional channel between browser and webserver usually run over an
upgraded HTTP(S) connection. Data is exchanged in messages whereby a message can either be binary data or unicode text.

Akka HTTP provides a stream-based implementation of the WebSocket protocol that hides the low-level details of the
underlying binary framing wire-protocol and provides a simple API to implement services using WebSocket.


Model
-----

The basic unit of data exchange in the WebSocket protocol is a message. A message can either be binary message,
i.e. a sequence of octets or a text message, i.e. a sequence of unicode code points.

Akka HTTP provides a straight-forward model for this abstraction:

.. includecode:: /../../akka-http-core/src/main/scala/akka/http/scaladsl/model/ws/Message.scala
   :include: message-model

The data of a message is provided as a stream because WebSocket messages do not have a predefined size and could
(in theory) be infinitely long. However, only one message can be open per direction of the WebSocket connection,
so that many application level protocols will want to make use of the delineation into (small) messages to transport
single application-level data units like "one event" or "one chat message".

Many messages are small enough to be sent or received in one go. As an opportunity for optimization, the model provides
a ``Strict`` subclass for each kind of message which contains data as a strict, i.e. non-streamed, ``ByteString`` or
``String``.

For sending data, the ``Strict`` variant is often the natural choice when the complete message has already been
assembled. While receiving data from the network connection the WebSocket implementation tries to create a ``Strict``
message whenever possible, i.e. when the complete data was received in one chunk. However, the actual chunking
of messages over a network connection and through the various streaming abstraction layers is not deterministic from
the perspective of the application. Therefore application code must be able to handle both streaming and strict messages
and not expect certain messages to be strict. (Particularly, note that tests against ``localhost`` will behave
differently from when data is received over a physical network connection.)


Core-level support
------------------

On the server-side a request to upgrade the connection to WebSocket is provided through a special header that is added
to a request by the implementation. Whenever a request contains the synthetic
``akka.http.scaldsl.model.ws.UpgradeToWebsocket``-header an HTTP request was a valid WebSocket upgrade request.
Methods on this header can be used to create a response that will upgrade the connection to a WebSocket connection and
install a ``Flow`` to handle WebSocket traffic on this connection.

The following example shows how to handle a WebSocket request using just the low-level http-core API:

.. includecode2:: ../../code/docs/http/scaladsl/server/WebsocketExampleSpec.scala
   :snippet: websocket-example-using-core

Handshake
+++++++++

HTTP-level details of the WebSocket handshake are hidden from the application. The ``UpgradeToWebsocket`` represents a
valid handshake request. The WebSocket protocol defines a facility to negotiate an application-level sub-protocol for
the WebSocket connection. Use ``UpgradeToWebsocket.requestedProtocols`` to retrieve the protocols suggested by the
client and pass one of the values to ``UpgradeToWebsocket.handleMessages`` or one of the other handling methods to
select a sub-protocol.

Handling Messages
+++++++++++++++++

A message handler is expected to be implemented as a ``Flow[Message, Message, Any]``. For typical request-response
scenarios this fits very well and such a ``Flow`` can be constructed from a simple function by using
``Flow[Message].map`` or ``Flow[Message].mapAsync``.

There are other typical use-cases, however, like a server-push model where a server message is sent spontaneously, or
true bi-directional use-cases where input and output aren't logically connected. Providing the handler as a ``Flow`` in
these cases seems awkward. A variant of ``UpgradeToWebsocket.handleMessages``,
``UpgradeToWebsocket.handleMessageWithSinkSource`` is provided instead, which allows for supplying a ``Sink[Message]``
and a ``Source[Message]`` for input and output independently.

Note that a handler is required to consume the data stream of each message to make place for new messages. Otherwise,
subsequent messages may be stuck and message traffic in this direction will stall.

Routing support
---------------

The routing DSL provides the :ref:`-handleWebsocketMessages-` directive to install a WebSocket handler if the request
was a WebSocket request. Otherwise, the directive rejects the request.

Complete example
----------------

.. includecode2:: ../../code/docs/http/scaladsl/server/WebsocketExampleSpec.scala
   :snippet: websocket-example-using-routing
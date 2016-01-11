.. _http-low-level-server-side-api:

Low-Level Server-Side API
=========================

Apart from the :ref:`HTTP Client <http-client-side>` Akka HTTP also provides an embedded,
`Reactive-Streams`_-based, fully asynchronous HTTP/1.1 server implemented on top of :ref:`Akka Stream <streams-scala>`.

It sports the following features:

- Full support for `HTTP persistent connections`_
- Full support for `HTTP pipelining`_
- Full support for asynchronous HTTP streaming including "chunked" transfer encoding accessible through an idiomatic API
- Optional SSL/TLS encryption
- Websocket support

.. _HTTP persistent connections: http://en.wikipedia.org/wiki/HTTP_persistent_connection
.. _HTTP pipelining: http://en.wikipedia.org/wiki/HTTP_pipelining
.. _Reactive-Streams: http://www.reactive-streams.org/


The server-side components of Akka HTTP are split into two layers:

1. The basic low-level server implementation in the ``akka-http-core`` module
2. Higher-level functionality in the ``akka-http`` module

The low-level server (1) is scoped with a clear focus on the essential functionality of an HTTP/1.1 server:

- Connection management
- Parsing and rendering of messages and headers
- Timeout management (for requests and connections)
- Response ordering (for transparent pipelining support)

All non-core features of typical HTTP servers (like request routing, file serving, compression, etc.) are left to
the higher layers, they are not implemented by the ``akka-http-core``-level server itself.
Apart from general focus this design keeps the server core small and light-weight as well as easy to understand and
maintain.

Depending on your needs you can either use the low-level API directly or rely on the high-level
:ref:`Routing DSL <http-high-level-server-side-api>` which can make the definition of more complex service logic much
easier.


Streams and HTTP
----------------

The Akka HTTP server is implemented on top of :ref:`Akka Stream <streams-scala>` and makes heavy use of it - in its
implementation as well as on all levels of its API.

On the connection level Akka HTTP offers basically the same kind of interface as :ref:`Akka Stream IO <stream-io-scala>`:
A socket binding is represented as a stream of incoming connections. The application pulls connections from this stream
source and, for each of them, provides a ``Flow[HttpRequest, HttpResponse, _]`` to "translate" requests into responses.

Apart from regarding a socket bound on the server-side as a ``Source[IncomingConnection]`` and each connection as a
``Source[HttpRequest]`` with a ``Sink[HttpResponse]`` the stream abstraction is also present inside a single HTTP
message: The entities of HTTP requests and responses are generally modeled as a ``Source[ByteString]``. See also
the :ref:`http-model-scala` for more information on how HTTP messages are represented in Akka HTTP.


Starting and Stopping
---------------------

On the most basic level an Akka HTTP server is bound by invoking the ``bind`` method of the `akka.http.scaladsl.Http`_
extension:

.. includecode2:: ../code/docs/http/scaladsl/HttpServerExampleSpec.scala
   :snippet: binding-example

Arguments to the ``Http().bind`` method specify the interface and port to bind to and register interest in handling
incoming HTTP connections. Additionally, the method also allows for the definition of socket options as well as a larger
number of settings for configuring the server according to your needs.

The result of the ``bind`` method is a ``Source[Http.IncomingConnection]`` which must be drained by the application in
order to accept incoming connections.
The actual binding is not performed before this source is materialized as part of a processing pipeline. In
case the bind fails (e.g. because the port is already busy) the materialized stream will immediately be terminated with
a respective exception.
The binding is released (i.e. the underlying socket unbound) when the subscriber of the incoming
connection source has cancelled its subscription. Alternatively one can use the ``unbind()`` method of the
``Http.ServerBinding`` instance that is created as part of the connection source's materialization process.
The ``Http.ServerBinding`` also provides a way to get a hold of the actual local address of the bound socket, which is
useful for example when binding to port zero (and thus letting the OS pick an available port).

.. _akka.http.scaladsl.Http: @github@/akka-http-core/src/main/scala/akka/http/scaladsl/Http.scala


Request-Response Cycle
----------------------

When a new connection has been accepted it will be published as an ``Http.IncomingConnection`` which consists
of the remote address and methods to provide a ``Flow[HttpRequest, HttpResponse, _]`` to handle requests coming in over
this connection.

Requests are handled by calling one of the ``handleWithXXX`` methods with a handler, which can either be

  - a ``Flow[HttpRequest, HttpResponse, _]`` for ``handleWith``,
  - a function ``HttpRequest => HttpResponse`` for ``handleWithSyncHandler``,
  - a function ``HttpRequest => Future[HttpResponse]`` for ``handleWithAsyncHandler``.

Here is a complete example:

.. includecode2:: ../code/docs/http/scaladsl/HttpServerExampleSpec.scala
  :snippet: full-server-example

In this example, a request is handled by transforming the request stream with a function ``HttpRequest => HttpResponse``
using ``handleWithSyncHandler`` (or equivalently, Akka Stream's ``map`` operator). Depending on the use case many
other ways of providing a request handler are conceivable using Akka Stream's combinators.

If the application provides a ``Flow`` it is also the responsibility of the application to generate exactly one response
for every request and that the ordering of responses matches the ordering of the associated requests (which is relevant
if HTTP pipelining is enabled where processing of multiple incoming requests may overlap). When relying on
``handleWithSyncHandler`` or ``handleWithAsyncHandler``, or the ``map`` or ``mapAsync`` stream operators, this
requirement will be automatically fulfilled.


Streaming Request/Response Entities
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Streaming of HTTP message entities is supported through subclasses of ``HttpEntity``. The application needs to be able
to deal with streamed entities when receiving a request as well as, in many cases, when constructing responses.
See :ref:`HttpEntity` for a description of the alternatives.

If you rely on the :ref:`http-marshalling-scala` and/or :ref:`http-unmarshalling-scala` facilities provided by
Akka HTTP then the conversion of custom types to and from streamed entities can be quite convenient.


Closing a connection
~~~~~~~~~~~~~~~~~~~~

The HTTP connection will be closed when the handling ``Flow`` cancels its upstream subscription or the peer closes the
connection. An often times more convenient alternative is to explicitly add a ``Connection: close`` header to an
``HttpResponse``. This response will then be the last one on the connection and the server will actively close the
connection when it has been sent out.


.. _serverSideHTTPS:

Server-Side HTTPS Support
-------------------------

Akka HTTP supports TLS encryption on the server-side as well as on the :ref:`client-side <clientSideHTTPS>`.

The central vehicle for configuring encryption is the ``HttpsContext``, which is defined as such:

.. includecode2:: /../../akka-http-core/src/main/scala/akka/http/scaladsl/Http.scala
   :snippet: https-context-impl

On the server-side the ``bind``, and ``bindAndHandleXXX`` methods of the `akka.http.scaladsl.Http`_ extension define an
optional ``httpsContext`` parameter, which can receive the HTTPS configuration in the form of an ``HttpsContext``
instance.
If defined encryption is enabled on all accepted connections. Otherwise it is disabled (which is the default).

.. _http-server-layer-scala:

Stand-Alone HTTP Layer Usage
----------------------------

Due to its Reactive-Streams-based nature the Akka HTTP layer is fully detachable from the underlying TCP
interface. While in most applications this "feature" will not be crucial it can be useful in certain cases to be able
to "run" the HTTP layer (and, potentially, higher-layers) against data that do not come from the network but rather
some other source. Potential scenarios where this might be useful include tests, debugging or low-level event-sourcing
(e.g by replaying network traffic).

On the server-side the stand-alone HTTP layer forms a ``BidiFlow`` that is defined like this:

.. includecode2:: /../../akka-http-core/src/main/scala/akka/http/scaladsl/Http.scala
   :snippet: server-layer

You create an instance of ``Http.ServerLayer`` by calling one of the two overloads of the ``Http().serverLayer`` method,
which also allows for varying degrees of configuration.

.. _handling-http-server-failures-low-level-scala:

Handling HTTP Server failures in the Low-Level API
--------------------------------------------------

There are various situations when failure may occur while initialising or running an Akka HTTP server.
Akka by default will log all these failures, however sometimes one may want to react to failures in addition to them
just being logged, for example by shutting down the actor system, or notifying some external monitoring end-point explicitly.

There are multiple things that can fail when creating and materializing an HTTP Server (similarily, the same applied to
a plain streaming ``Tcp()`` server). The types of failures that can happen on different layers of the stack, starting
from being unable to start the server, and ending with failing to unmarshal an HttpRequest, examples of failures include
(from outer-most, to inner-most):

- Failure to ``bind`` to the specified address/port,
- Failure while accepting new ``IncommingConnection`` s, for example when the OS has run out of file descriptors or memory,
- Failure while handling a connection, for example if the incoming ``HttpRequest`` is malformed.

This section describes how to handle each failure situation, and in which situations these failures may occur.

Bind failures
^^^^^^^^^^^^^

The first type of failure is when the server is unable to bind to the given port. For example when the port
is already taken by another application, or if the port is privileged (i.e. only usable by ``root``).
In this case the "binding future" will fail immediatly, and we can react to if by listening on the Future's completion:

.. includecode2:: ../code/docs/http/scaladsl/HttpServerExampleSpec.scala
  :snippet: binding-failure-handling

Once the server has successfully bound to a port, the ``Source[IncomingConnection, _]`` starts running and emiting
new incoming connections. This source technically can signal a failure as well, however this should only happen in very
dramantic situations such as running out of file descriptors or memory available to the system, such that it's not able
to accept a new incoming connection. Handling failures in Akka Streams is pretty stright forward, as failures are signaled
through the stream starting from the stage which failed, all the way downstream to the final stages.

Connections Source failures
^^^^^^^^^^^^^^^^^^^^^^^^^^^

In the example below we add a custom ``PushStage`` (see :ref:`stream-customize-scala`) in order to react to the
stream's failure. We signal a ``failureMonitor`` actor with the cause why the stream is going down, and let the Actor
handle the rest – maybe it'll decide to restart the server or shutdown the ActorSystem, that however is not our concern anymore.

.. includecode2:: ../code/docs/http/scaladsl/HttpServerExampleSpec.scala
  :snippet: incoming-connections-source-failure-handling

Connection failures
^^^^^^^^^^^^^^^^^^^

The third type of failure that can occur is when the connection has been properly established,
however afterwards is terminated abruptly – for example by the client aborting the underlying TCP connection.
To handle this failure we can use the same pattern as in the previous snippet, however apply it to the connection's Flow:

.. includecode2:: ../code/docs/http/scaladsl/HttpServerExampleSpec.scala
  :snippet: connection-stream-failure-handling

These failures can be described more or less infrastructure related, they are failing bindings or connections.
Most of the time you won't need to dive into those very deeply, as Akka will simply log errors of this kind
anyway, which is a reasonable default for such problems.

In order to learn more about handling exceptions in the actual routing layer, which is where your application code
comes into the picture, refer to :ref:`exception-handling-scala` which focuses explicitly on explaining how exceptions
thrown in routes can be handled and transformed into :class:`HttpResponse` s with apropriate error codes and human-readable failure descriptions.

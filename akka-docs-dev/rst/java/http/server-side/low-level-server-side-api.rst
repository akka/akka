.. _http-low-level-server-side-api-java:

Low-Level Server-Side API
=========================

Apart from the :ref:`HTTP Client <http-client-side-java>` Akka HTTP also provides an embedded,
`Reactive-Streams`_-based, fully asynchronous HTTP/1.1 server implemented on top of :ref:`Akka Stream <streams-java>`.

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
:ref:`Routing DSL <http-high-level-server-side-api-java>` which can make the definition of more complex service logic much
easier.


Streams and HTTP
----------------

The Akka HTTP server is implemented on top of :ref:`Akka Stream <streams-java>` and makes heavy use of it - in its
implementation as well as on all levels of its API.

On the connection level Akka HTTP offers basically the same kind of interface as :ref:`Akka Stream IO <stream-io-java>`:
A socket binding is represented as a stream of incoming connections. The application pulls connections from this stream
source and, for each of them, provides a ``Flow<HttpRequest, HttpResponse, ?>`` to "translate" requests into responses.

Apart from regarding a socket bound on the server-side as a ``Source<IncomingConnection>`` and each connection as a
``Source<HttpRequest>`` with a ``Sink<HttpResponse>`` the stream abstraction is also present inside a single HTTP
message: The entities of HTTP requests and responses are generally modeled as a ``Source<ByteString>``. See also
the :ref:`http-model-java` for more information on how HTTP messages are represented in Akka HTTP.


Starting and Stopping
---------------------

On the most basic level an Akka HTTP server is bound by invoking the ``bind`` method of the `akka.http.javadsl.Http`_
extension:

.. includecode:: ../../code/docs/http/javadsl/server/HttpServerExampleDocTest.java
   :include: binding-example

Arguments to the ``Http().bind`` method specify the interface and port to bind to and register interest in handling
incoming HTTP connections. Additionally, the method also allows for the definition of socket options as well as a larger
number of settings for configuring the server according to your needs.

The result of the ``bind`` method is a ``Source<Http.IncomingConnection>`` which must be drained by the application in
order to accept incoming connections.
The actual binding is not performed before this source is materialized as part of a processing pipeline. In
case the bind fails (e.g. because the port is already busy) the materialized stream will immediately be terminated with
a respective exception.
The binding is released (i.e. the underlying socket unbound) when the subscriber of the incoming
connection source has cancelled its subscription. Alternatively one can use the ``unbind()`` method of the
``Http.ServerBinding`` instance that is created as part of the connection source's materialization process.
The ``Http.ServerBinding`` also provides a way to get a hold of the actual local address of the bound socket, which is
useful for example when binding to port zero (and thus letting the OS pick an available port).

.. _akka.http.javadsl.Http: @github@/akka-http-core/src/main/scala/akka/http/javadsl/Http.scala

.. _http-low-level-server-side-example-java:

Request-Response Cycle
----------------------

When a new connection has been accepted it will be published as an ``Http.IncomingConnection`` which consists
of the remote address and methods to provide a ``Flow<HttpRequest, HttpResponse, ?>`` to handle requests coming in over
this connection.

Requests are handled by calling one of the ``handleWithXXX`` methods with a handler, which can either be

  - a ``Flow<HttpRequest, HttpResponse, ?>`` for ``handleWith``,
  - a function ``Function<HttpRequest, HttpResponse>`` for ``handleWithSyncHandler``,
  - a function ``Function<HttpRequest, Future<HttpResponse>>`` for ``handleWithAsyncHandler``.

Here is a complete example:

.. includecode:: ../../code/docs/http/javadsl/server/HttpServerExampleDocTest.java
  :include: full-server-example

In this example, a request is handled by transforming the request stream with a function ``Function<HttpRequest, HttpResponse>``
using ``handleWithSyncHandler`` (or equivalently, Akka Stream's ``map`` operator). Depending on the use case many
other ways of providing a request handler are conceivable using Akka Stream's combinators.

If the application provides a ``Flow`` it is also the responsibility of the application to generate exactly one response
for every request and that the ordering of responses matches the ordering of the associated requests (which is relevant
if HTTP pipelining is enabled where processing of multiple incoming requests may overlap). When relying on
``handleWithSyncHandler`` or ``handleWithAsyncHandler``, or the ``map`` or ``mapAsync`` stream operators, this
requirement will be automatically fulfilled.

See :ref:`routing-java` for a more convenient high-level DSL to create request handlers.

Streaming Request/Response Entities
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Streaming of HTTP message entities is supported through subclasses of ``HttpEntity``. The application needs to be able
to deal with streamed entities when receiving a request as well as, in many cases, when constructing responses.
See :ref:`HttpEntity-java` for a description of the alternatives.


Closing a connection
~~~~~~~~~~~~~~~~~~~~

The HTTP connection will be closed when the handling ``Flow`` cancels its upstream subscription or the peer closes the
connection. An often times more convenient alternative is to explicitly add a ``Connection: close`` header to an
``HttpResponse``. This response will then be the last one on the connection and the server will actively close the
connection when it has been sent out.


.. _serverSideHTTPS-java:

Server-Side HTTPS Support
-------------------------

Akka HTTP supports TLS encryption on the server-side as well as on the :ref:`client-side <clientSideHTTPS-java>`.

The central vehicle for configuring encryption is the ``HttpsContext``, which can be created using ``HttpsContext.create``
which is defined like this:

.. includecode:: /../../akka-http-core/src/main/java/akka/http/javadsl/HttpsContext.java
   :include: http-context-creation

On the server-side the ``bind``, and ``bindAndHandleXXX`` methods of the `akka.http.javadsl.Http`_ extension define an
optional ``httpsContext`` parameter, which can receive the HTTPS configuration in the form of an ``HttpsContext``
instance.
If defined encryption is enabled on all accepted connections. Otherwise it is disabled (which is the default).


Stand-Alone HTTP Layer Usage
----------------------------

// TODO
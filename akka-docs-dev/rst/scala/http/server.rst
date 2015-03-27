.. _http-core-server-scala:

Server API
==========

The Akka HTTP server is an embedded, stream-based, fully asynchronous, low-overhead
HTTP/1.1 server implemented on top of :ref:`streams-scala`.

It sports the following features:

- Full support for `HTTP persistent connections`_
- Full support for `HTTP pipelining`_
- Full support for asynchronous HTTP streaming including "chunked" transfer encoding accessible through an idiomatic
  reactive streams API
- Optional SSL/TLS encryption

.. _HTTP persistent connections: http://en.wikipedia.org/wiki/HTTP_persistent_connection
.. _HTTP pipelining: http://en.wikipedia.org/wiki/HTTP_pipelining


Design Philosophy
-----------------

Akka HTTP server is scoped with a clear focus on the essential functionality of an HTTP/1.1 server:

- Connection management
- Parsing messages and headers
- Timeout management (for requests and connections)
- Response ordering (for transparent pipelining support)

All non-core features of typical HTTP servers (like request routing, file serving, compression, etc.) are left to
the next-higher layer in the application stack, they are not implemented by the HTTP server itself.
Apart from general focus this design keeps the server small and light-weight as well as easy to understand and
maintain.


Basic Architecture
------------------

Akka HTTP server is implemented on top of Akka streams and makes heavy use of them - in its
implementation and also on all levels of its API.

On the connection level Akka HTTP supports basically the same interface as Akka streams IO: A socket binding is
represented as a stream of incoming connections. The application needs to provide a ``Flow[HttpRequest, HttpResponse]``
to "translate" requests into responses.

Streaming is also supported for single message entities itself. Particular kinds of ``HttpEntity``
subclasses provide support for fixed or streamed message entities.


Starting and Stopping
---------------------

An Akka HTTP server is bound by invoking the ``bind`` method of the `akka.http.Http`_ extension:

.. includecode:: ../code/docs/http/HttpServerExampleSpec.scala
   :include: bind-example

Arguments to the ``Http.bind`` method specify the interface and port to bind to and register interest in handling incoming
HTTP connections. Additionally, the method also allows you to define socket options as well as a larger number
of settings for configuring the server according to your needs.

The result of the ``bind`` method is a ``Source[IncomingConnection]`` which is used to handle incoming connections.
The actual binding is only done when this source is materialized as part of a bigger processing pipeline. In case the
bind fails (e.g. because the port is already busy) the error will be reported by flagging an error on the materialized
stream. The binding is released and the underlying listening socket is closed when all subscribers of the
source have cancelled their subscription.

Connections are handled by materializing a pipeline which uses the ``Source[IncomingConnection]``. This source
materializes to ``Future[ServerBinding]`` which is completed when server successfully binds to the specified port.
After materialization ``ServerBinding.localAddress`` returns the actual local address of the bound socket.
``ServerBinding.unbind()`` can be used to asynchronously trigger unbinding of the server port.

(todo: explain even lower level serverFlowToTransport API)

.. _akka.http.Http: @github@/akka-http-core/src/main/scala/akka/http/Http.scala


Request-Response Cycle
----------------------

When a new connection has been accepted it will be published as an ``Http.IncomingConnection`` which consists
of the remote address, and methods to provide a ``Flow[HttpRequest, HttpResponse]`` to handle requests coming in over
this connection.

Requests are handled by calling one of the ``IncomingConnection.handleWithX`` methods with a handler, which can either be

  - a ``Flow[HttpRequest, HttpResponse]`` for ``handleWith``,
  - a function ``HttpRequest => HttpResponse`` for ``handleWithSyncHandler``,
  - or a function ``HttpRequest => Future[HttpResponse]`` for ``handleWithAsyncHandler``.

.. includecode:: ../code/docs/http/HttpServerExampleSpec.scala
   :include: full-server-example

In this case, a request is handled by transforming the request stream with a function ``HttpRequest => HttpResponse``
using ``handleWithSyncHandler`` (or equivalently, Akka stream's ``map`` operator). Depending on the use case, arbitrary
other ways of connecting are conceivable using Akka stream's combinators.

If the application provides a ``Flow``, it is also the responsibility of the application to generate exactly one response
for every request and that the ordering of responses matches the ordering of the associated requests (which is relevant
if HTTP pipelining is enabled where processing of multiple incoming requests may overlap). Using ``handleWithSyncHandler``
or ``handleWithAsyncHandler`` or, instead, if using stream operators like ``map`` or ``mapFuture`` this requirement
will automatically be fulfilled.

Streaming request/response entities
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Streaming of HTTP message entities is supported through subclasses of ``HttpEntity``. You need to be able to deal
with streamed entities when receiving a request as well as when constructing responses. See :ref:`HttpEntity` for
a description of the alternatives.

(todo): Link to :ref:`http-routing-scala` for (un-)marshalling facilities.


Closing a connection
~~~~~~~~~~~~~~~~~~~~

The HTTP connection will be closed when the handling ``Flow`` cancel its upstream subscription or the peer closes the
connection.

You can also use the value of the ``Connection`` header of a response as described below to give a hint to the
implementation to close the connection after the completion of the response.

HTTP Headers
------------

When the Akka HTTP server receives an HTTP request it tries to parse all its headers into their respective
model classes. No matter whether this succeeds or not, the connection actor will always pass on all
received headers to the application. Unknown headers as well as ones with invalid syntax (according to the header
parser) will be made available as ``RawHeader`` instances. For the ones exhibiting parsing errors a warning message is
logged depending on the value of the ``illegal-header-warnings`` config setting.

Some common headers are treated specially in the model and in the implementation and should not occur in the ``headers``
field of an HTTP message:

- ``Content-Type``: Use the ``contentType`` field of the ``HttpEntity`` subclasses to set or determine the content-type
  on an entity.
- ``Transfer-Encoding``: The ``Transfer-Encoding`` is represented by subclasses of ``HttpEntity``.
- ``Content-Length``: The ``Content-Length`` header is represented implicitly by the choice of an ``HttpEntity`` subclass:
  A Strict entity determines the Content-Length by the length of the data provided. A Default entity has an explicit
  ``contentLength`` field which specifies the amount of data the streaming producer will produce. Chunked and CloseDelimited
  entities don't need to define a length.
- ``Server``: The ``Server`` header is usually added automatically and it's value can be configured. An application can
  decide to provide a custom ``Server`` header by including an explicit instance in the response.
- ``Date``: The ``Date`` header is added automatically and can be overridden by supplying it manually.
- ``Connection``: When sending out responses the connection actor watches for a ``Connection`` header set by the
  application and acts accordingly, i.e. you can force the connection actor to close the connection after having sent
  the response by including a ``Connection("close")`` header. To unconditionally force a connection keep-alive you can
  explicitly set a ``Connection("Keep-Alive")`` header. If you don't set an explicit ``Connection`` header the
  connection actor will keep the connection alive if the client supports this (i.e. it either sent a
  ``Connection: Keep-Alive`` header or advertised HTTP/1.1 capabilities without sending a ``Connection: close`` header).

SSL Support
-----------

(todo)

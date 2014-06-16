.. _http-core-server-scala:

HTTP Server
===========

The Akka HTTP server is an embedded, stream-based, fully asynchronous, low-overhead
HTTP/1.1 server implemented on top of `Akka Streams`_. (todo: fix link)

It sports the following features:

- Low per-connection overhead for supporting many thousand concurrent connections
- Efficient message parsing and processing logic for high throughput applications
- Full support for `HTTP persistent connections`_
- Full support for `HTTP pipelining`_
- Full support for asynchronous HTTP streaming (including "chunked" transfer encoding) accessible through an idiomatic
  reactive streams API
- Optional SSL/TLS encryption

.. _HTTP persistent connections: http://en.wikipedia.org/wiki/HTTP_persistent_connection
.. _HTTP pipelining: http://en.wikipedia.org/wiki/HTTP_pipelining
.. _Akka streams: http://akka.io/docs/


Design Philosophy
-----------------

Akka HTTP server is scoped with a clear focus on the essential functionality of an HTTP/1.1 server:

- Connection management
- Message parsing and header separation
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
represented as a stream of incoming connections. Each connection itself is composed of an input stream of requests and
an output consumer of responses. The application has to provide the handler to "translate" requests into responses.

Streaming is also supported for single message entities itself. Particular kinds of ``HttpEntity``
subclasses provide support for fixed or streamed message entities.


Starting and Stopping
---------------------

An Akka HTTP server is started by sending an ``Http.Bind`` command to the `akka.http.Http`_ extension:

.. includecode:: code/docs/http/HttpServerExampleSpec.scala
   :include: bind-example

With the ``Http.Bind`` command you specify the interface and port to bind to and register interest in handling incoming
HTTP connections. Additionally the ``Http.Bind`` command also allows you to define socket options as well as a larger number
of settings for configuring the server according to your needs.

The sender of the ``Http.Bind`` command (e.g. an actor you have written) will receive an ``Http.ServerBinding`` reply
after the HTTP layer has successfully started the server at the respective endpoint. In case the bind fails (e.g.
because the port is already busy) a ``Failure`` message is dispatched instead. As shown in the above example this works
well with the ask pattern and Future operations.

The ``Http.ServerBinding`` informs the binder of the actual local address of the bound socket and it contains a
stream of incoming connections of type ``Producer[Http.IncomingConnection]``. Connections are handled by subscribing
to the connection stream and handling the incoming connections.

The binding is released and the underlying listening socket is closed when all subscribers of the
``Http.ServerBinding.connectionStream`` have cancelled their subscriptions.

.. _akka.http.Http: @github@/akka-http-core/src/main/scala/akka/http/Http.scala


Request-Response Cycle
----------------------

When a new connection has been accepted it will be published by the ``Http.ServerBinding.connectionStream`` as an
``Http.IncomingConnection`` which consists of the remote address, a ``requestProducer``, and a ``responseConsumer``.

Handling requests in this model means connecting the ``requestProducer`` stream with an application-defined component that
maps requests to responses which then feeds into the ``responseConsumer``:

.. includecode:: code/docs/http/HttpServerExampleSpec.scala
   :include: full-server-example

In this case, a request is handled by transforming the request stream with a function ``HttpRequest => HttpResponse``
using Akka stream's ``map`` operator. Depending on the use case, arbitrary other ways of connecting are conceivable using
Akka stream's operators (e.g using ``mapFuture`` to allow parallel processing of several requests when HTTP pipelining is
enabled).

It's the application's responsibility to feed responses into the ``responseConsumer`` in the same order as the respective
requests have come in. Also, each request must result in exactly one response. Using stream operators like ``map`` or
``mapFuture`` will automatically fulfill this requirement.

Streaming request/response entities
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Streaming of HTTP message entities is supported through subclasses of ``HttpEntity``. You need to be able to deal
with streamed entities when receiving a request as well as when constructing responses. See :ref:`HttpEntity` for
a description of the alternatives.

(todo): Link to :ref:`http-routing-scala` for (un-)marshalling facilities.


Closing a connection
~~~~~~~~~~~~~~~~~~~~

The HTTP connection will be closed when the ``responseConsumer`` gets completed or when the ``requestProducer``'s
subscription was cancelled and no more responses are pending.

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
- ``Date``: The ``Date`` header is added automatically and will be ignored if supplied manually.
- ``Connection``: When sending out responses the connection actor watches for a ``Connection`` header set by the
  application and acts accordingly, i.e. you can force the connection actor to close the connection after having sent
  the response by including a ``Connection("close")`` header. To unconditionally force a connection keep-alive you can
  explicitly set a ``Connection("Keep-Alive")`` header. If you don't set an explicit ``Connection`` header the
  connection actor will keep the connection alive if the client supports this (i.e. it either sent a
  ``Connection: Keep-Alive`` header or advertised HTTP/1.1 capabilities without sending a ``Connection: close`` header).

SSL Support
-----------

(todo)
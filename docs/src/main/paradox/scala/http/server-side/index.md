# Server-Side API

Apart from the @ref[HTTP Client](../client-side/index.md) Akka HTTP also provides an embedded,
[Reactive-Streams](http://www.reactive-streams.org/)-based, fully asynchronous HTTP/1.1 server implemented on top of @scala[@extref[Streams](akka-docs:scala/stream/index.html)]@java[@extref[Streams](akka-docs:java/stream/index.html)].

It sports the following features:

 * Full support for [HTTP persistent connections](http://en.wikipedia.org/wiki/HTTP_persistent_connection)
 * Full support for [HTTP pipelining](http://en.wikipedia.org/wiki/HTTP_pipelining)
 * Full support for asynchronous HTTP streaming including "chunked" transfer encoding accessible through an idiomatic API
 * Optional SSL/TLS encryption
 * WebSocket support

The server-side components of Akka HTTP are split into two layers:

@ref[Low-Level Server-Side API](low-level-api.md)
:  The basic low-level server implementation in the `akka-http-core` module.

@ref[High-level Server-Side API](../routing-dsl/index.md)
:  Higher-level functionality in the `akka-http` module which offers a very flexible "Routing DSL" for elegantly defining RESTful web services as well as
   functionality of typical web servers or frameworks, like deconstruction of URIs, content negotiation or
   static content serving.

Depending on your needs you can either use the low-level API directly or rely on the high-level
@ref[Routing DSL](../routing-dsl/index.md) which can make the definition of more complex service logic much
easier. You can also interact with different API levels at the same time and, independently of which API level you choose
Akka HTTP will happily serve many thousand concurrent connections to a single or many different clients.

@@@ note
It is recommended to read the @ref[Implications of the streaming nature of Request/Response Entities](../implications-of-streaming-http-entity.md) section,
as it explains the underlying full-stack streaming concepts, which may be unexpected when coming
from a background with non-"streaming first" HTTP Servers.
@@@

@@toc { depth=3 }

@@@ index

* [low-level-api](low-level-api.md)
* [websocket-support](websocket-support.md)
* [server-https-support](server-https-support.md)
* [http2](http2.md)

@@@

# Consuming HTTP-based Services (Client-Side)

All client-side functionality of Akka HTTP, for consuming HTTP-based services offered by other endpoints, is currently
provided by the `akka-http-core` module.

It is recommended to first read the @ref[Implications of the streaming nature of Request/Response Entities](../implications-of-streaming-http-entity.md) section,
as it explains the underlying full-stack streaming concepts, which may be unexpected when coming
from a background with non-"streaming first" HTTP Clients.

Depending on your application's specific needs you can choose from three different API levels:

@ref[Request-Level Client-Side API](request-level.md)
: for letting Akka HTTP perform all connection management. Recommended for most usages.

@ref[Host-Level Client-Side API](host-level.md)
: for letting Akka HTTP manage a connection-pool to *one specific* host/port endpoint. Recommended when
  the user can supply a @scala[@unidoc[Source[HttpRequest, NotUsed]]]@java[@unidoc[Source[HttpRequest, NotUsed]]] with requests to run against a single host
  over multiple pooled connections.

@ref[Connection-Level Client-Side API](connection-level.md)
: for full control over when HTTP connections are opened/closed and how requests are scheduled across them. Only
  recommended for particular use cases.

You can interact with different API levels at the same time and, independently of which API level you choose,
Akka HTTP will happily handle many thousand concurrent connections to a single or many different hosts.

@@toc { depth=3 }

@@@ index

* [request-level](request-level.md)
* [host-level](host-level.md)
* [connection-level](connection-level.md)
* [pool-overflow](pool-overflow.md)
* [client-https-support](client-https-support.md)
* [client-transport](client-transport.md)
* [websocket-support](websocket-support.md)

@@@

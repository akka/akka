# Akka HTTP Timeouts

Akka HTTP comes with a variety of built-in timeout mechanisms to protect your servers from malicious attacks or
programming mistakes. Some of these are simply configuration options (which may be overridden in code) while others
are left to the streaming APIs and are easily implementable as patterns in user-code directly.

## Common timeouts

<a id="idle-timeouts"></a>
### Idle timeouts

The `idle-timeout` is a global setting which sets the maximum inactivity time of a given connection.
In other words, if a connection is open but no request/response is being written to it for over `idle-timeout` time,
the connection will be automatically closed.

The setting works the same way for all connections, be it server-side or client-side, and it's configurable
independently for each of those using the following keys:

```
akka.http.server.idle-timeout
akka.http.client.idle-timeout
akka.http.host-connection-pool.idle-timeout
akka.http.host-connection-pool.client.idle-timeout
```

@@@ note
For the client side connection pool, the idle period is counted only when the pool has no pending requests waiting.
@@@

## Server timeouts

<a id="request-timeout"></a>
### Request timeout

Request timeouts are a mechanism that limits the maximum time it may take to produce an @unidoc[HttpResponse] from a route.
If that deadline is not met the server will automatically inject a Service Unavailable HTTP response and close the connection
to prevent it from leaking and staying around indefinitely (for example if by programming error a Future would never complete,
never sending the real response otherwise).

The default @unidoc[HttpResponse] that is written when a request timeout is exceeded looks like this:

@@snip [HttpServerBluePrint.scala]($akka-http$/akka-http-core/src/main/scala/akka/http/impl/engine/server/HttpServerBluePrint.scala) { #default-request-timeout-httpresponse }

A default request timeout is applied globally to all routes and can be configured using the
`akka.http.server.request-timeout` setting (which defaults to 20 seconds).

@@@ note
Please note that if multiple requests (`R1,R2,R3,...`) were sent by a client (see "HTTP pipelining")
using the same connection and the `n-th` request triggers a request timeout the server will reply with an Http Response
and close the connection, leaving the `(n+1)-th` (and subsequent requests on the same connection) unhandled.
@@@

The request timeout can be configured at run-time for a given route using the any of the @ref[TimeoutDirectives](../routing-dsl/directives/timeout-directives/index.md).

### Bind timeout

The bind timeout is the time period within which the TCP binding process must be completed (using any of the `Http().bind*` methods).
It can be configured using the `akka.http.server.bind-timeout` setting.

### Linger timeout

The linger timeout is the time period the HTTP server implementation will keep a connection open after
all data has been delivered to the network layer. This setting is similar to the SO_LINGER socket option
but does not only include the OS-level socket but also covers the Akka IO / Akka Streams network stack.
The setting is an extra precaution that prevents clients from keeping open a connection that is
already considered completed from the server side.

If the network level buffers (including the Akka Stream / Akka IO networking stack buffers)
contains more data than can be transferred to the client in the given time when the server-side considers
to be finished with this connection, the client may encounter a connection reset.

Set to `infinite` to disable automatic connection closure (which will risk to leak connections).

## Client timeouts

### Connecting timeout

The connecting timeout is the time period within which the TCP connecting process must be completed.
Tweaking it should rarely be required, but it allows erroring out the connection in case a connection
is unable to be established for a given amount of time.

it can be configured using the `akka.http.client.connecting-timeout` setting.

# Pool overflow and the max-open-requests setting

@ref[Request-Level Client-Side API](request-level.md) and @ref[Host-Level Client-Side API](host-level.md)
use a connection pool underneath. The connection pool will open a limited number of concurrent connections to one host
(see the `akka.http.host-connection-pool.max-connections` setting). This will limit the rate of requests a pool
to a single host can handle.

When you use the @ref[stream-based host-level API](host-level.md#using-the-host-level-api-in-a-streaming-fashion)
stream semantics prevent that the pool is overloaded with requests. On the other side, when a new request is pushed either using
@scala[`Http().singleRequest()`]@java[`Http.get(system).singleRequest()`] or when materializing too many streams using the same
@scala[`Http().cachedHostConnectionPool`]@java[`Http.get(system).cachedHostConnectionPool`], requests may start to queue
up when the rate of new requests is greater than the rate at which the pool can process requests.

In such a situation `max-open-requests` per host connection pool will be queued to buffer short-term peaks of requests.
Further requests will fail immediately with a @unidoc[BufferOverflowException] with a message like this:

```
Exceeded configured max-open-requests value of ...
```

This will usually happen under high load or when the pool has been running for some time with the processing speed being
too slow to handle all the incoming requests.

Note, that even if the pool can handle regular load, short-term hiccups (at the server, the network, or at the client) can make
the queue overflow, so you need to treat this as an expected condition. Your application should be able to deal with it. In many cases, it
makes sense to treat pool overflow the same as a `503` answer from the server which usually is used when the server is
overloaded. A common way to treat it would be to retry the request after some while (using a viable backoff strategy).

## Common causes of pool overload

As explained above the general explanation for pool overload is that the incoming request rate is higher that the request
processing rate. This can have all kinds of causes (and hints for fixing them in parentheses):

 * The server is too slow (improve server performance)
 * The network is too slow (improve network performance)
 * The client issues requests too fast (slow down creation of requests if possible)
 * There's high latency between client and server (use more concurrent connections to hide latency with parallelism)
 * There are peaks in the request rate (prevent peaks by tuning the client application or increase `max-open-requests` to
   buffer short-term peaks)
 * Response entities were not read or discarded (see @ref[Implications of the streaming nature of Http entities](../implications-of-streaming-http-entity.md))
 * Some requests are slower than others blocking the connections of a pool for other requests (see below)

The last point may need a bit more explanation. If some requests are much slower than others, e.g. if the request is
a long-running Server Sent Events request than this will block one of the connections of the pool for a long time. If
there are multiple such requests going on at the same time it will lead to starvation and other requests cannot make any
progress any more. Make sure to run a long-running request on a dedicated connection (using the
@ref[Connection-Level Client-Side API](connection-level.md)) to prevent such a situation.

## Why does this happen only with Akka Http and not with [insert other client]

Many Java HTTP clients don't set limits by default for some of the resources used. E.g. some clients will never queue a
request but will just open another connection to the server if all the pooled connections are currently busy. However,
this might just move the problem from the client to the server. Also using an excessive number of connections will lead to
worse performance on the network as more connections will compete for bandwidth.
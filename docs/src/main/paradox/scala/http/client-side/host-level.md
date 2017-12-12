# Host-Level Client-Side API

As opposed to the @ref[Connection-Level Client-Side API](connection-level.md) the host-level API relieves you from manually managing individual HTTP
connections. It autonomously manages a configurable pool of connections to *one particular target endpoint* (i.e.
host/port combination).

@@@ note
It is recommended to first read the @ref[Implications of the streaming nature of Request/Response Entities](../implications-of-streaming-http-entity.md) section,
as it explains the underlying full-stack streaming concepts, which may be unexpected when coming
from a background with non-"streaming first" HTTP Clients.
@@@

## Requesting a Host Connection Pool

The best way to get a hold of a connection pool to a given target endpoint is the @scala[`Http().cachedHostConnectionPool(...)`]@java[`Http.get(system).cachedHostConnectionPool(...)`]
method, which returns a @unidoc[Flow] that can be "baked" into an application-level stream setup. This flow is also called
a "pool client flow".

The connection pool underlying a pool client flow is cached. For every @unidoc[ActorSystem], target endpoint and pool
configuration there will never be more than a single pool live at any time.

Also, the HTTP layer transparently manages idle shutdown and restarting of connection pools as configured.
The client flow instances therefore remain valid throughout the lifetime of the application, i.e. they can be
materialized as often as required and the time between individual materialization is of no importance.

When you request a pool client flow with @scala[`Http().cachedHostConnectionPool(...)`]@java[`Http.get(system).cachedHostConnectionPool(...)`], Akka HTTP will immediately start
the pool, even before the first client flow materialization. However, this running pool will not actually open the
first connection to the target endpoint until the first request has arrived.

## Configuring a Host Connection Pool

Apart from the connection-level config settings and socket options there are a number of settings that allow you to
influence the behavior of the connection pool logic itself.
Check out the `akka.http.host-connection-pool` section of the Akka HTTP @ref[Configuration](../configuration.md) for
more information about which settings are available and what they mean.

Note that, if you request pools with different configurations for the same target host you will get *independent* pools.
This means that, in total, your application might open more concurrent HTTP connections to the target endpoint than any
of the individual pool's `max-connections` settings allow!

There is one setting that likely deserves a bit deeper explanation: `max-open-requests`.
This setting limits the maximum number of requests that can be in-flight at any time for a single connection pool.
If an application calls @scala[`Http().cachedHostConnectionPool(...)`]@java[`Http.get(system).cachedHostConnectionPool(...)`]
3 times (with the same endpoint and settings) it will get
back `3` different client flow instances for the same pool. If each of these client flows is then materialized `4` times
(concurrently) the application will have 12 concurrently running client flow materializations.
All of these share the resources of the single pool.

This means that, if the pool's `pipelining-limit` is left at `1` (effectively disabling pipelining), no more than 12 requests can be open at any time.
With a `pipelining-limit` of `8` and 12 concurrent client flow materializations the theoretical open requests
maximum is `96`.

The `max-open-requests` config setting allows for applying a hard limit which serves mainly as a protection against
erroneous connection pool use, e.g. because the application is materializing too many client flows that all compete for
the same pooled connections.

<a id="using-a-host-connection-pool"></a>
## Using a Host Connection Pool

The "pool client flow" returned by @scala[`Http().cachedHostConnectionPool(...)`]@java[`Http.get(system).cachedHostConnectionPool(...)`] has the following type:

@@@ div { .group-scala }
```scala
Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool]
```
@@@
@@@ div { .group-java }
```java
Flow<Pair<HttpRequest, T>, Pair<Try<HttpResponse>, T>, HostConnectionPool>
```
@@@

This means it consumes pairs of type @scala[`(HttpRequest, T)`]@java[@unidoc[Pair[HttpRequest, T]]] and produces pairs of type @scala[`(Try[HttpResponse], T)`]@java[`Pair<Try<HttpResponse>, T>`]
which might appear more complicated than necessary on first sight.
The reason why the pool API includes objects of custom type `T` on both ends lies in the fact that the underlying
transport usually comprises more than a single connection and as such the pool client flow often generates responses in
an order that doesn't directly match the consumed requests.
We could have built the pool logic in a way that reorders responses according to their requests before dispatching them
to the application, but this would have meant that a single slow response could block the delivery of potentially many
responses that would otherwise be ready for consumption by the application.

In order to prevent unnecessary head-of-line blocking the pool client-flow is allowed to dispatch responses as soon as
they arrive, independently of the request order. Of course this means that there needs to be another way to associate a
response with its respective request. The way that this is done is by allowing the application to pass along a custom
"context" object with the request, which is then passed back to the application with the respective response.
This context object of type `T` is completely opaque to Akka HTTP, i.e. you can pick whatever works best for your
particular application scenario.

@@@ note

A consequence of using a pool is that long-running requests block a connection while running and may starve other
requests. Make sure not to use a connection pool for long-running requests like long-polling GET requests.
Use the @ref[Connection-Level Client-Side API](connection-level.md) instead.

@@@

## Connection Allocation Logic

This is how Akka HTTP allocates incoming requests to the available connection "slots":

 1. If there is a connection alive and currently idle then schedule the request across this connection.
 2. If no connection is idle and there is still an unconnected slot then establish a new connection.
 3. If all connections are already established and "loaded" with other requests then pick the connection with the least
open requests (< the configured `pipelining-limit`) that only has requests with idempotent methods scheduled to it,
if there is one.
 4. Otherwise apply back-pressure to the request source, i.e. stop accepting new requests.

For more information about scheduling more than one request at a time across a single connection see
[this Wikipedia entry on HTTP pipelining](http://en.wikipedia.org/wiki/HTTP_pipelining).

## Retrying a Request

If the `max-retries` pool config setting is greater than zero the pool retries idempotent requests for which
a response could not be successfully retrieved. Idempotent requests are those whose HTTP method is defined to be
idempotent by the HTTP spec, which are all the ones currently modelled by Akka HTTP except for the `POST`, `PATCH`
and `CONNECT` methods.

When a response could not be received for a certain request there are essentially three possible error scenarios:

 1. The request got lost on the way to the server.
 2. The server experiences a problem while processing the request.
 3. The response from the server got lost on the way back.

Since the host connector cannot know which one of these possible reasons caused the problem and therefore `PATCH` and
`POST` requests could have already triggered a non-idempotent action on the server these requests cannot be retried.

In these cases, as well as when all retries have not yielded a proper response, the pool produces a failed `Try`
(i.e. a `scala.util.Failure`) together with the custom request context.

## Pool Shutdown

Completing a pool client flow will simply detach the flow from the pool. The connection pool itself will continue to run
as it may be serving other client flows concurrently or in the future. Only after the configured `idle-timeout` for
the pool has expired will Akka HTTP automatically terminate the pool and free all its resources.

If a new client flow is requested with @scala[`Http().cachedHostConnectionPool(...)`]@java[`Http.get(system).cachedHostConnectionPool(...)`] or if an already existing client flow is
re-materialized the respective pool is automatically and transparently restarted.

In addition to the automatic shutdown via the configured idle timeouts it's also possible to trigger the immediate
shutdown of a specific pool by calling `shutdown()` on the `HostConnectionPool` instance that the pool client
flow materializes into. This `shutdown()` call produces a @scala[`Future[Unit]`]@java[`CompletionStage<Done>`] which is fulfilled when the pool
termination has been completed.

It's also possible to trigger the immediate termination of *all* connection pools in the @unidoc[ActorSystem] at the same
time by calling @scala[`Http().shutdownAllConnectionPools()`]@java[`Http.get(system).shutdownAllConnectionPools()`].
This call too produces a @scala[`Future[Unit]`]@java[`CompletionStage<Done>`] which is fulfilled when all pools have terminated.

@@@ note
When encountering unexpected `akka.stream.AbruptTerminationException` exceptions during @unidoc[ActorSystem] **shutdown**
please make sure that active connections are shut down before shutting down the entire system, this can be done by
calling the @scala[`Http().shutdownAllConnectionPools()`]@java[`Http.get(system).shutdownAllConnectionPools()`] method,
and only once its @scala[`Future`]@java[`CompletionStage`] completes, shutting down the actor system.
@@@

## Examples

@@@ note { .group-scala }
At this place we previously showed an example that used the `Source.single(request).via(pool).runWith(Sink.head)`. In
fact, this is an anti-pattern that doesn't perform well. Please either supply requests using a queue or in a streamed fashion as
shown below.
@@@

@@@ div { .group-scala }

### Using the host-level API with a queue

In many cases, you just want to issue requests to a pool and receive responses when they are available. In most cases,
you should use the @ref[Request-Level Client-Side API](request-level.md) for this purpose. If you want to use a similar Future-based API
with the host-level API, here's how to do it.

As explained above, Akka HTTP prevents to build up an unbounded buffer of requests and an unlimited number of connections.
Therefore, it guards itself a) by applying backpressure to all request streams connected to the cached pool and b)
by failing requests with a @unidoc[BufferOverflowException] when the internal buffer overflows when too many materializations
exist or too many requests have been issued to the pool.

To mimic the request-level API we can put an explicit queue in front of the pool and decide ourselves what to do when
this explicit queue overflows. This example shows how to do this. (Thanks go to [kazuhiro's blog for the initial idea](http://kazuhiro.github.io/scala/akka/akka-http/akka-streams/2016/01/31/connection-pooling-with-akka-http-and-source-queue.html).)

You can tweak the `QueueSize` setting according to your memory constraints. In any case, you need to think about a strategy
about what to do when requests fail because the queue overflowed (e.g. try again later or just fail).

@@snip [HttpClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpClientExampleSpec.scala) { #host-level-queue-example }

### Using the host-level API in a streaming fashion

Even better is it to use the streaming API directly. This will mostly prevent intermediate buffers as data can be
generated "on-the-fly" while streaming the requests. You supply the requests as a stream, i.e. as a @unidoc[Source[(HttpRequest, ...)]], and
the pool will "pull out" single requests when capacity is available on one of the connections to the host.

@@snip [HttpClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpClientExampleSpec.scala) { #host-level-streamed-example }
@@@

@@@ div { .group-java }
For now, please see the Scala examples in @ref[Scala Host-Level Client API](../../../scala/http/client-side/host-level.md#examples).
If you want to help with converting the examples see issue [#836](https://github.com/akka/akka-http/issues/836).
@@@

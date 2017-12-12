# Connection-Level Client-Side API

The connection-level API is the lowest-level client-side API Akka HTTP provides. It gives you full control over when
HTTP connections are opened and closed and how requests are to be send across which connection. As such it offers the
highest flexibility at the cost of providing the least convenience.

@@@ note
It is recommended to first read the @ref[Implications of the streaming nature of Request/Response Entities](../implications-of-streaming-http-entity.md) section,
as it explains the underlying full-stack streaming concepts, which may be unexpected when coming
from a background with non-"streaming first" HTTP Clients.
@@@

## Opening HTTP Connections

With the connection-level API you open a new HTTP connection to a target endpoint by materializing a @unidoc[Flow]
returned by the @scala[`Http().outgoingConnection(...)`]@java[`Http.get(system).outgoingConnection(...)`] method.
Here is an example:

Scala
:  @@snip [HttpClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpClientExampleSpec.scala) { #outgoing-connection-example }

Java
:  @@snip [HttpClientExampleDocTest.java]($test$/java/docs/http/javadsl/HttpClientExampleDocTest.java) { #outgoing-connection-example }

Apart from the host name and port the @scala[`Http().outgoingConnection(...)`]@java[`Http.get(system).outgoingConnection(...)`]
method also allows you to specify socket options and a number of configuration settings for the connection.

Note that no connection is attempted until the returned flow is actually materialized! If the flow is materialized
several times then several independent connections will be opened (one per materialization).
If the connection attempt fails, for whatever reason, the materialized flow will be immediately terminated with a
respective exception.

## Request-Response Cycle

Once the connection flow has been materialized it is ready to consume @unidoc[HttpRequest] instances from the source it is
attached to. Each request is sent across the connection and incoming responses dispatched to the downstream pipeline.
Of course and as always, back-pressure is adequately maintained across all parts of the
connection. This means that, if the downstream pipeline consuming the HTTP responses is slow, the request source will
eventually be slowed down in sending requests.

Any errors occurring on the underlying connection are surfaced as exceptions terminating the response stream (and
canceling the request source).

Note that, if the source produces subsequent requests before the prior responses have arrived, these requests will be
[pipelined](http://en.wikipedia.org/wiki/HTTP_pipelining) across the connection, which is something that is not supported by all HTTP servers.
Also, if the server closes the connection before responses to all requests have been received this will result in the
response stream being terminated with a truncation error.

## Closing Connections

Akka HTTP actively closes an established connection upon reception of a response containing `Connection: close` header.
The connection can also be closed by the server.

An application can actively trigger the closing of the connection by completing the request stream. In this case the
underlying TCP connection will be closed when the last pending response has been received.

The connection will also be closed if the response entity is cancelled (e.g. by attaching it to `Sink.cancelled()`)
or consumed only partially (e.g. by using `take` combinator). In order to prevent this behaviour the entity should be
explicitly drained by attaching it to `Sink.ignore()`.

## Timeouts

Currently Akka HTTP doesn't implement client-side request timeout checking itself as this functionality can be regarded
as a more general purpose streaming infrastructure feature.

It should be noted that Akka Streams provide various timeout functionality so any API that uses streams can benefit
from the stream stages such as `idleTimeout`, `backpressureTimeout`, `completionTimeout`, `initialTimeout`
and `throttle`. To learn more about these refer to their documentation in Akka Streams.

For more details about timeout support in Akka HTTP in general refer to @ref[Akka HTTP Timeouts](../common/timeouts.md).

<a id="http-client-layer"></a>
## Stand-Alone HTTP Layer Usage

Due to its Reactive-Streams-based nature the Akka HTTP layer is fully detachable from the underlying TCP
interface. While in most applications this "feature" will not be crucial it can be useful in certain cases to be able
to "run" the HTTP layer (and, potentially, higher-layers) against data that do not come from the network but rather
some other source. Potential scenarios where this might be useful include tests, debugging or low-level event-sourcing
(e.g by replaying network traffic).

On the client-side the stand-alone HTTP layer forms a `BidiStage` stage that "upgrades" a potentially encrypted raw connection to the HTTP level.
It is defined like this:

@@@ div { .group-scala }
@@snip [Http.scala]($akka-http$/akka-http-core/src/main/scala/akka/http/scaladsl/Http.scala) { #client-layer }
@@@
@@@ div { .group-java }
```java
BidiFlow<HttpRequest, SslTlsOutbound, SslTlsInbound, HttpResponse, NotUsed>
```
@@@

You create an instance of @scala[`Http.ClientLayer`]@java[the layer] by calling one of the two overloads
of the @scala[`Http().clientLayer`]@java[`Http.get(system).clientLayer`] method,
which also allows for varying degrees of configuration.

# Client-Side WebSocket Support

Client side WebSocket support is available through @scala[`Http().singleWebSocketRequest`]@java[`Http.get(system).singleWebSocketRequest`],
@scala[`Http().webSocketClientFlow`]@java[`Http.get(system).webSocketClientFlow`] and @scala[`Http().webSocketClientLayer`]@java[`Http.get(system).webSocketClientLayer`].

A WebSocket consists of two streams of messages, incoming messages (a @unidoc[Sink]) and outgoing messages
(a @unidoc[Source]) where either may be signalled first; or even be the only direction in which messages flow during
the lifetime of the connection. Therefore a WebSocket connection is modelled as either something you connect a
@scala[@unidoc[Flow[Message, Message, Mat]`]@java[@unidoc[Flow[Message, Message, Mat]]] to or a @scala[`Flow[Message, Message, Mat]`]@java[@unidoc[Flow[Message, Message, Mat]]] that you connect a @scala[@unidoc[Source[Message, Mat]`]@java[@unidoc[Source[Message, Mat]]] and
a @scala[@unidoc[Sink[Message, Mat]`]@java[@unidoc[Sink[Message, Mat]]] to.

A WebSocket request starts with a regular HTTP request which contains an `Upgrade` header (and possibly
other regular HTTP request properties), so in addition to the flow of messages there also is an initial response
from the server, this is modelled with @unidoc[WebSocketUpgradeResponse].

The methods of the WebSocket client API handle the upgrade to WebSocket on connection success and materializes
the connected WebSocket stream. If the connection fails, for example with a `404 NotFound` error, this regular
HTTP result can be found in `WebSocketUpgradeResponse.response`

@@@ note
Make sure to read and understand the section about [Half-Closed WebSockets](#half-closed-client-websockets) as the behavior
when using WebSockets for one-way communication may not be what you would expect.
@@@

## Message

Messages sent and received over a WebSocket can be either @unidoc[TextMessage] s or @unidoc[BinaryMessage] s and each
of those has two subtypes `Strict` (all data in one chunk) or `Streamed`. In typical applications messages will be `Strict` as
WebSockets are usually deployed to communicate using small messages not stream data, the protocol does however
allow this (by not marking the first fragment as final, as described in [RFC 6455 section 5.2](https://tools.ietf.org/html/rfc6455#section-5.2)).

The strict text is available from @scala[`TextMessage.Strict`]@java[`TextMessage.getStrictText`] and strict binary data from
@scala[`BinaryMessage.Strict`]@java[`BinaryMessage.getStrictData`].

For streamed messages @scala[`BinaryMessage.Streamed`]@java[`BinaryMessage.getStreamedData`] and @scala[`TextMessage.Streamed`]@java[`TextMessage.getStreamedText`] will be used.
In these cases the data is provided as a @scala[`Source[ByteString, NotUsed]]]@java[@unidoc[Source[ByteString, NotUsed]]] for binary and @scala[`Source[String, NotUsed]]]@java[@unidoc[Source[String, NotUsed]]] for text messages.

## singleWebSocketRequest

`singleWebSocketRequest` takes a @unidoc[WebSocketRequest] and a flow it will connect to the source and
sink of the WebSocket connection. It will trigger the request right away and returns a tuple containing the
@scala[`Future[WebSocketUpgradeResponse]]]@java[`CompletionStage<WebSocketUpgradeResponse>`] and the materialized value from the flow passed to the method.

The future will succeed when the WebSocket connection has been established or the server returned a regular
HTTP response, or fail if the connection fails with an exception.

Simple example sending a message and printing any incoming message:

Scala
:   @@snip [WebSocketClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/WebSocketClientExampleSpec.scala) { #single-WebSocket-request }

Java
:   @@snip [WebSocketClientExampleTest.java]($test$/java/docs/http/javadsl/WebSocketClientExampleTest.java) { #single-WebSocket-request }

The websocket request may also include additional headers, like in this example, HTTP Basic Auth:

Scala
:   @@snip [WebSocketClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/WebSocketClientExampleSpec.scala) { #authorized-single-WebSocket-request }

Java
:   @@snip [WebSocketClientExampleTest.java]($test$/java/docs/http/javadsl/WebSocketClientExampleTest.java) { #authorized-single-WebSocket-request }

## webSocketClientFlow

`webSocketClientFlow` takes a request, and returns a @scala[@unidoc[Flow[Message, Message, Future[WebSocketUpgradeResponse]]]]@java[`Flow<Message, Message, CompletionStage<WebSocketUpgradeResponse>>`].

The future that is materialized from the flow will succeed when the WebSocket connection has been established or
the server returned a regular HTTP response, or fail if the connection fails with an exception.

@@@ note
The @unidoc[Flow] that is returned by this method can only be materialized once. For each request a new
flow must be acquired by calling the method again.
@@@

Simple example sending a message and printing any incoming message:

Scala
:   @@snip [WebSocketClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/WebSocketClientExampleSpec.scala) { #WebSocket-client-flow }

Java
:   @@snip [WebSocketClientExampleTest.java]($test$/java/docs/http/javadsl/WebSocketClientExampleTest.java) { #WebSocket-client-flow }

## webSocketClientLayer

Just like the @ref[Stand-Alone HTTP Layer Usage](connection-level.md#http-client-layer) for regular HTTP requests, the WebSocket layer can be used fully detached from the
underlying TCP interface. The same scenarios as described for regular HTTP requests apply here.

The returned layer forms a @scala[@unidoc[BidiFlow[Message, SslTlsOutbound, SslTlsInbound, Message, Future[WebSocketUpgradeResponse]]]]@java[`BidiFlow<Message, SslTlsOutbound, SslTlsInbound, Message, CompletionStage<WebSocketUpgradeResponse>>`].

<a id="half-closed-client-websockets"></a>
## Half-Closed WebSockets

The Akka HTTP WebSocket API does not support half-closed connections which means that if either stream completes the
entire connection is closed (after a "Closing Handshake" has been exchanged or a timeout of 3 seconds has passed).
This may lead to unexpected behavior, for example if we are trying to only consume messages coming from the server,
like this:

Scala
:   @@snip [WebSocketClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/WebSocketClientExampleSpec.scala) { #half-closed-WebSocket-closing-example }

Java
:   @@snip [WebSocketClientExampleTest.java]($test$/java/docs/http/javadsl/WebSocketClientExampleTest.java) { #half-closed-WebSocket-closing }

This will in fact quickly close the connection because of the @scala[`Source.empty`]@java[`Source.empty()`] being completed immediately when the
stream is materialized. To solve this you can make sure to not complete the outgoing source by using for example
@scala[`Source.maybe`]@java[`Source.maybe()`] like this:

Scala
:   @@snip [WebSocketClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/WebSocketClientExampleSpec.scala) { #half-closed-WebSocket-working-example }

Java
:   @@snip [WebSocketClientExampleTest.java]($test$/java/docs/http/javadsl/WebSocketClientExampleTest.java) { #half-closed-WebSocket-working }

This will keep the outgoing source from completing, but without emitting any elements until the @scala[`Promise`]@java[`CompletableFuture`] is manually
completed which makes the @unidoc[Source] complete and the connection to close.

The same problem holds true if emitting a finite number of elements, as soon as the last element is reached the @unidoc[Source]
will close and cause the connection to close. To avoid that you can concatenate @scala[`Source.maybe`]@java[`Source.maybe()`] to the finite stream:

Scala
:   @@snip [WebSocketClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/WebSocketClientExampleSpec.scala) { #half-closed-WebSocket-finite-working-example }

Java
:   @@snip [WebSocketClientExampleTest.java]($test$/java/docs/http/javadsl/WebSocketClientExampleTest.java) { #half-closed-WebSocket-finite }

Scenarios that exist with the two streams in a WebSocket and possible ways to deal with it:

|Scenario                              | Possible solution                                                                                                    |
|--------------------------------------|----------------------------------------------------------------------------------------------------------------------|
|Two-way communication                 | `Flow.fromSinkAndSource`, or `Flow.map` for a request-response protocol                                              |
|Infinite incoming stream, no outgoing | @scala[`Flow.fromSinkAndSource(someSink, Source.maybe)`]@java[`Flow.fromSinkAndSource(someSink, Source.maybe())`]    |
|Infinite outgoing stream, no incoming | @scala[`Flow.fromSinkAndSource(Sink.ignore, yourSource)`]@java[``Flow.fromSinkAndSource(Sink.ignore(), yourSource)``]|

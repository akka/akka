<a id="sse-support-scala"></a>
# Server-Sent Events Support

Server-Sent Events (SSE) is a lightweight and [standardized](http://www.w3.org/TR/eventsource)
protocol for pushing notifications from a HTTP server to a client. In contrast to WebSocket, which
offers bi-directional communication, SSE only allows for one-way communication from the server to
the client. If that's all you need, SSE has the advantages to be much simpler, to rely on HTTP only
and to offer retry semantics on broken connections by the browser.

According to the SSE specification clients can request an event stream from the server via HTTP. The
server responds with the media type `text/event-stream` which has the fixed character encoding UTF-8
and keeps the response open to send events to the client when available. Events are textual
structures which carry fields and are terminated by an empty line, e.g.

```
data: { "username": "John Doe" }
event: added
id: 42

data: another event
```

Clients can optionally signal the last seen event to the server via the `Last-Event-ID` header, e.g.
after a reconnect.

## Model

Akka HTTP represents event streams as `Source<ServerSentEvent, NotUsed>` where `ServerSentEvent` is a
class with the following read-only properties:

- `String data` – the actual payload, may span multiple lines
- `Optional<String> type` – optional qualifier, e.g. "added", "removed", etc.
- `Optional<String> id` – optional identifier
- `OptionalInt retry` – optional reconnection delay in milliseconds

In accordance to the SSE specification Akka HTTP also provides the `LastEventId` header and the
`TEXT_EVENT_STREAM` media type.

## Server-side usage: marshalling

In order to respond to a HTTP request with an event stream, one has to use the
`EventStreamMarshalling.toEventStream` marshaller:

@@snip [EventStreamMarshallingTest.java](../../../../../../akka-http-tests/src/test/java/akka/http/javadsl/marshalling/sse/EventStreamMarshallingTest.java) { #event-stream-marshalling-example }

## Client-side usage: unmarshalling

In order to unmarshal an event stream as `Source<ServerSentEvent, NotUsed>`, one has to use the
`EventStreamUnmarshalling.fromEventStream` unmarshaller:

@@snip [EventStreamMarshallingTest.java](../../../../../../akka-http-tests/src/test/java/akka/http/javadsl/unmarshalling/sse/EventStreamUnmarshallingTest.java) { #event-stream-unmarshalling-example }

Notice that if you are looking for a resilient way to permanently subscribe to an event stream,
Alpakka provides the [EventSource](http://developer.lightbend.com/docs/alpakka/current/sse.html)
connector which reconnects automatically with the id of the last seen event.

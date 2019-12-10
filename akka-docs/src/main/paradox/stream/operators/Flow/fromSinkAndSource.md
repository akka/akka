# Flow.fromSinkAndSource

Creates a `Flow` from a `Sink` and a `Source` where the Flow's input will be sent to the `Sink` and the `Flow` 's output will come from the Source.

@ref[Flow operators composed of Sinks and Sources](../index.md#flow-operators-composed-of-sinks-and-sources)

## Signature

@apidoc[Flow.fromSinkAndSource](Flow$) { scala="#fromSinkAndSource[I,O](sink:akka.stream.Graph[akka.stream.SinkShape[I],_],source:akka.stream.Graph[akka.stream.SourceShape[O],_]):akka.stream.scaladsl.Flow[I,O,akka.NotUsed]" java="#fromSinkAndSource(akka.stream.Graph,akka.stream.Graph)" }

## Description

<img src="../../../images/fromSinkAndSource.png" alt="Diagram" width="350"/>

`fromSinkAndSource` combines a separate `Sink` and `Source` into a `Flow`.

Useful in many cases where an API requires a `Flow` but you want to provide a `Sink` and `Source` whose flows of elements are decoupled.

Note that termination events, like completion and cancelation is not automatically propagated through to the "other-side" of the such-composed Flow. The `Source` can complete and the sink will continue to accept elements.

Use @ref:[fromSinkAndSourceCoupled](fromSinkAndSourceCoupled.md) if you want to couple termination of both of the ends, for example most useful in handling websocket connections.


## Examples

One use case is for constructing a TCP server which where request and response does not map 1:1 (like it does in the @ref[Echo TCP server sample](../../stream-io.md) where every incoming test is echoed back) but allows separete flows of elements from the client to the server and from the server to the client.

This example `cancel`s the incoming stream, not allowing the client to write more messages, switching the TCP connection to "half-closed", but keeps streaming periodic output to the client:

Scala
:   @@snip [FromSinkAndSource.scala](/akka-docs/src/test/scala/docs/stream/operators/flow/FromSinkAndSource.scala) { #halfClosedTcpServer }

Java
:   @@snip [FromSinkAndSource.java](/akka-docs/src/test/java/jdocs/stream/operators/flow/FromSinkAndSource.java) { #halfClosedTcpServer }

With this server running you could use `telnet 127.0.0.1 9999` to see a stream of timestamps being printed, one every second. 

The same pattern can also be applied to [Akka HTTP WebSockets](https://doc.akka.io/docs/akka-http/current/server-side/websocket-support.html#server-api) which also has an API accepting a `Flow` of messages. 

## Reactive Streams semantics

@@@div { .callout }

**emits** when the `Source` emits

**backpressures** when the `Sink` backpressures 

**completes** when the `Source` has completed and the `Sink` has cancelled. 

@@@

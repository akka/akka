# StreamConverters.asInputStream

Create a sink which materializes into an `InputStream` that can be read to trigger demand through the sink.

@ref[Additional Sink and Source converters](../index.md#additional-sink-and-source-converters)

## Signature

@apidoc[StreamConverters.asInputStream](StreamConverters$) { scala="#asInputStream(readTimeout:scala.concurrent.duration.FiniteDuration):akka.stream.scaladsl.Sink[akka.util.ByteString,java.io.InputStream]" java="#asInputStream()" }


## Description

Create a sink which materializes into an `InputStream` that can be read to trigger demand through the sink.
Bytes emitted through the stream will be available for reading through the `InputStream`

The `InputStream` will be ended when the stream flowing into this `Sink` completes, and the closing the
`InputStream` will cancel the inflow of this `Sink`.

## Reactive Streams semantics

@@@div { .callout }
**cancels** when the `InputStream` is closed

**backpressures** when no read is pending on the `InputStream`
@@@


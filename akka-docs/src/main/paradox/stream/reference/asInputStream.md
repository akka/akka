# asInputStream

Create a sink which materializes into an `InputStream` that can be read to trigger demand through the sink.

@@@ div { .group-scala }
## Signature

@@signature [StreamConverters.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/StreamConverters.scala) { #asInputStream }
@@@

## Description

Create a sink which materializes into an `InputStream` that can be read to trigger demand through the sink.
Bytes emitted through the stream will be available for reading through the `InputStream`

The `InputStream` will be ended when the stream flowing into this `Sink` completes, and the closing the
`InputStream` will cancel the inflow of this `Sink`.

@@@div { .callout }
**cancels** when the `InputStream` is closed

**backpressures** when no read is pending on the `InputStream`
@@@

## Example


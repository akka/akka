# StreamConverters.asOutputStream

Create a source that materializes into an `OutputStream`.

@ref[Additional Sink and Source converters](../index.md#additional-sink-and-source-converters)

@@@ div { .group-scala }
## Signature

@@signature [StreamConverters.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/StreamConverters.scala) { #asOutputStream }
@@@

## Description

Create a source that materializes into an `OutputStream`. When bytes are written to the `OutputStream` they
are emitted from the source.

The `OutputStream` will no longer be writable when the `Source` has been canceled from its downstream, and
closing the `OutputStream` will complete the `Source`.

## Reactive Streams semantics

@@@div { .callout }
**emits** when bytes are written to the `OutputStream`

**completes** when the `OutputStream` is closed
@@@


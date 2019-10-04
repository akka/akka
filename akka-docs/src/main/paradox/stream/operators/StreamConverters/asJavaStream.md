# StreamConverters.asJavaStream

Create a sink which materializes into Java 8 `Stream` that can be run to trigger demand through the sink.

@ref[Additional Sink and Source converters](../index.md#additional-sink-and-source-converters)

@@@ div { .group-scala }
## Signature

@@signature [StreamConverters.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/StreamConverters.scala) { #asJavaStream }
@@@

## Description

Create a sink which materializes into Java 8 `Stream` that can be run to trigger demand through the sink.
Elements emitted through the stream will be available for reading through the Java 8 `Stream`.

The Java 8 `Stream` will be ended when the stream flowing into this `Sink` completes, and closing the Java
`Stream` will cancel the inflow of this `Sink`. Java `Stream` throws exception in case reactive stream failed.

Be aware that Java `Stream` blocks current thread while waiting on next element from downstream.

## Reactive Streams semantics

@@@div { .callout }
**cancels** when the Java Stream is closed

**backpressures** when no read is pending on the Java Stream
@@@


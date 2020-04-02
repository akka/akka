# StreamConverters.fromOutputStream

Create a sink that wraps an `OutputStream`.

@ref[Additional Sink and Source converters](../index.md#additional-sink-and-source-converters)

## Signature

@apidoc[StreamConverters.fromOutputStream](StreamConverters$) { scala="#fromOutputStream(out:()=%3Cjava.io.OutputStream,autoFlush:Boolean):akka.stream.scaladsl.Sink[akka.util.ByteString,scala.concurrent.Future[akka.stream.IOResult]]" java="#fromOutputStream(akka.japi.function.Creator)" }

## Description

Creates a Sink which writes incoming @apidoc[akka.util.ByteString]s to a `java.io.OutputStream` created by the given function.

Materializes a @java[`CompletionStage`]@scala[`Future`] of `IOResult` that will be completed with the size of the file (in bytes) on completion,
and a possible exception if IO operation was not completed successfully.

You can configure the default dispatcher for this Source by changing the `akka.stream.materializer.blocking-io-dispatcher` or
set it for a given Source by using `akka.stream.ActorAttributes`.

If `autoFlush` is true the OutputStream will be flushed whenever a byte array is written, defaults to false.
The `OutputStream` will be closed when the stream flowing into this `Sink` is completed. The `Sink`
will cancel the stream when the `OutputStream` is no longer writable.

See also @ref:[fromInputStream](fromInputStream.md)

## Example

Here is an example using both `fromInputStream` and `fromOutputStream` to read from a `java.io.InputStream`, 
uppercase the read content and write back out into a `java.io.OutputStream`.

Scala
:   @@snip [ToFromJavaIOStreams.scala](/akka-docs/src/test/scala/docs/stream/operators/converters/ToFromJavaIOStreams.scala) { #tofromJavaIOStream }

Java
:   @@snip [ToFromJavaIOStreams.java](/akka-docs/src/test/java/jdocs/stream/operators/converters/ToFromJavaIOStreams.java) { #tofromJavaIOStream }


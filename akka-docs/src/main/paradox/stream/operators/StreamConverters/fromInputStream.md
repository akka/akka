# StreamConverters.fromInputStream

Create a source that wraps an `InputStream`.

@ref[Additional Sink and Source converters](../index.md#additional-sink-and-source-converters)

## Signature

@apidoc[StreamConverters.fromInputStream](StreamConverters$) { scala="#fromInputStream(in:()=%3Ejava.io.InputStream,chunkSize:Int):akka.stream.scaladsl.Source[akka.util.ByteString,scala.concurrent.Future[akka.stream.IOResult]]" java="#fromInputStream(akka.japi.function.Creator)" }

## Description

Creates a Source from a `java.io.InputStream` created by the given function.  Emitted elements are up to `chunkSize` 
sized @apidoc[akka.util.ByteString]s elements.  The actual size of the emitted elements depends on how much data the 
underlying `java.io.InputStream` returns on each read invocation. Such chunks will  never be larger 
than `chunkSize` though.

You can configure the default dispatcher for this Source by changing 
the `akka.stream.materializer.blocking-io-dispatcher` or set it for a given Source by 
using `akka.stream.ActorAttributes`.

It materializes a @java[`CompletionStage`]@scala[`Future`] of `IOResult` containing the number of bytes read from the source file 
upon completion,  and a possible exception if IO operation was not completed successfully. Note that bytes having
been read by the source does not give any guarantee that the bytes were seen by downstream stages.

The created `InputStream` will be closed when the `Source` is cancelled.

See also @ref:[fromOutputStream](fromOutputStream.md)


## Example

Here is an example using both `fromInputStream` and `fromOutputStream` to read from a `java.io.InputStream`, 
uppercase the read content and write back out into a `java.io.OutputStream`.

Scala
:   @@snip [ToFromJavaIOStreams.scala](/akka-docs/src/test/scala/docs/stream/operators/converters/ToFromJavaIOStreams.scala) { #tofromJavaIOStream }

Java
:   @@snip [ToFromJavaIOStreams.java](/akka-docs/src/test/java/jdocs/stream/operators/converters/ToFromJavaIOStreams.java) { #tofromJavaIOStream }


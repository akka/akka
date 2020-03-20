# Compression.gzip

Creates a flow that gzip-compresses a stream of ByteStrings.  

@ref[Compression operators](../index.md#compression-operators)

## Signature

@apidoc[Compression.gzip](stream.*.Compression$) { scala="#gzip:akka.stream.scaladsl.Flow[akka.util.ByteString,akka.util.ByteString,akka.NotUsed]" java="#gzip()" }

## Description

Creates a flow that gzip-compresses a stream of ByteStrings. Note that the compressor
will SYNC_FLUSH after every @apidoc[akka.util.ByteString] so that it is guaranteed that every @apidoc[akka.util.ByteString]
coming out of the flow can be fully decompressed without waiting for additional data. This may
come at a compression performance cost for very small chunks.

Use the overload method to control the compression level.

## Reactive Streams semantics

@@@div { .callout }

**emits** when the compression algorithm produces output for the received `ByteString`

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

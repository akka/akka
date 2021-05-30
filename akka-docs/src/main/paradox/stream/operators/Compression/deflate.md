# Compression.deflate

Creates a flow that deflate-compresses a stream of ByteStrings. 

@ref[Compression operators](../index.md#compression-operators)

## Signature

@apidoc[Compression.deflate](stream.*.Compression$) { scala="#deflate:akka.stream.scaladsl.Flow[akka.util.ByteString,akka.util.ByteString,akka.NotUsed]" java="#deflate()" }

## Description

Creates a flow that deflate-compresses a stream of ByteStrings. Note that the compressor
will SYNC_FLUSH after every @apidoc[akka.util.ByteString] so that it is guaranteed that every @apidoc[akka.util.ByteString]
coming out of the flow can be fully decompressed without waiting for additional data. This may
come at a compression performance cost for very small chunks.

Use the overload method with parameters to control the compression level and compatibility with GZip.  

## Reactive Streams semantics

@@@div { .callout }

**emits** when the compression algorithm produces output for the received `ByteString`

**backpressures** when downstream backpressures

**completes** when upstream completes (may emit finishing bytes in an extra `ByteString` )

@@@

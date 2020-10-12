# Compression.gunzip

Creates a flow that gzip-decompresses a stream of ByteStrings.  

@ref[Compression operators](../index.md#compression-operators)

## Signature

@apidoc[Compression.gunzip](stream.*.Compression$) { scala="#gunzip(maxBytesPerChunk:Int):akka.stream.scaladsl.Flow[akka.util.ByteString,akka.util.ByteString,akka.NotUsed]" java="#gunzip(int)" }

## Description

Creates a flow that gzip-decompresses a stream of ByteStrings. If the input is truncated, uses invalid 
compression method or is invalid (failed CRC checks) this operator fails with a `java.util.zip.ZipException`. 

## Reactive Streams semantics

@@@div { .callout }

**emits** when the decompression algorithm produces output for the received `ByteString` (the emitted `ByteString` is of `maxBytesPerChunk` maximum length)

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

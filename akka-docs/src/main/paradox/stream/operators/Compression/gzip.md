# Compression.gzip

Creates a flow that gzip-compresses a stream of ByteStrings.

@ref[Compression operators](../index.md#compression-operators)

@@@div { .group-scala }

## Dependency

This operator is included in:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-stream_$scala.binary_version$"
  version="$akka.version$"
}

## Signature

@@signature [Compression.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Compression.scala) { #gzip }

@@@

## Description

Creates a flow that gzip-compresses a stream of ByteStrings. Note that the compressor
will SYNC_FLUSH after every `ByteString` so that it is guaranteed that every `ByteString`
coming out of the flow can be fully decompressed without waiting for additional data. This may
come at a compression performance cost for very small chunks.

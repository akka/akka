# Compression.deflate

Creates a flow that deflate-compresses a stream of ByteString.

@ref[Actor interop operators](../index.md#actor-interop-operators)

@@@div { .group-scala }

## Dependency

This operator is included in:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-stream_$scala.binary_version$"
  version="$akka.version$"
}

## Signature

@@signature [Compression.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Compression.scala) { #deflate }

@@@

## Description

Creates a flow that deflate-compresses a stream of ByteString. Note that the compressor
will SYNC_FLUSH after every [[ByteString]] so that it is guaranteed that every [[ByteString]]
coming out of the flow can be fully decompressed without waiting for additional data. This may
come at a compression performance cost for very small chunks.

## Examples

TODO (in progress)

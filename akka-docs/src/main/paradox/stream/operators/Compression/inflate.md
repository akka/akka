# Compression.inflate

Creates a Flow that decompresses deflate-compressed stream of data.

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

@@signature [Compression.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Compression.scala) { #inflate }

@@@

## Description

Creates a flow that inflate-decompresses a stream of ByteString.

## Examples

TODO (in progress)

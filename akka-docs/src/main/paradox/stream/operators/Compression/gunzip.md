# Compression.gunzip

Creates a Flow that decompresses gzip-compressed stream of data.

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

@@signature [Compression.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Compression.scala) { #gunzip }

@@@

## Description

Creates a Flow that decompresses gzip-compressed stream of data.

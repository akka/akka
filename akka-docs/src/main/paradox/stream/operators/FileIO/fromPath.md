# FileIO.fromPath

Emit the contents of a file.

@ref[File IO Sinks and Sources](../index.md#file-io-sinks-and-sources)

@@@div { .group-scala }

## Signature

@@signature [FileIO.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/FileIO.scala) { #fromPath }

@@@

## Description

Emit the contents of a file, as `ByteString`s, materializes into a @scala[`Future`] @java[`CompletionStage`] which will be completed with
a `IOResult` upon reaching the end of the file or if there is a failure.

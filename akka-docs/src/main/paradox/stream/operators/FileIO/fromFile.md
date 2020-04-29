# FileIO.fromFile

Emits the contents of a file.

@ref[File IO Sinks and Sources](../index.md#file-io-sinks-and-sources)

@@@ warning

The `fromFile` operator has been deprecated, use @ref:[fromPath](./fromPath.md) instead. 

@@@

## Signature

@apidoc[FileIO.fromFile](FileIO$) { scala="#fromFile(f:java.io.File,chunkSize:Int):akka.stream.scaladsl.Source[akka.util.ByteString,scala.concurrent.Future[akka.stream.IOResult]]" java="#fromFile(java.io.File)" java="#fromFile(java.io.File,int)" }


## Description

Emits the contents of a file, as `ByteString`s, materializes into a @scala[`Future`] @java[`CompletionStage`] which will be completed with
a `IOResult` upon reaching the end of the file or if there is a failure.


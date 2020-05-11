# FileIO.toFile

Create a sink which will write incoming `ByteString` s to a given file.

@ref[File IO Sinks and Sources](../index.md#file-io-sinks-and-sources)

@@@ warning

The `toFile` operator has been deprecated, use @ref:[toPath](./toPath.md) instead. 

@@@

## Signature

@apidoc[FileIO.toFile](FileIO$) { scala="#toFile(f:java.io.File,options:Set[java.nio.file.OpenOption]):akka.stream.scaladsl.Sink[akka.util.ByteString,scala.concurrent.Future[akka.stream.IOResult]]" java="#toFile(java.io.File,java.util.Set)" }


## Description

Creates a Sink which writes incoming `ByteString` elements to the given file path. Overwrites existing files by truncating their contents as default. 
Materializes a @scala[`Future`] @java[`CompletionStage`] of `IOResult` that will be completed with the size of the file (in bytes) at the streams completion, and a possible exception if IO operation was not completed successfully.

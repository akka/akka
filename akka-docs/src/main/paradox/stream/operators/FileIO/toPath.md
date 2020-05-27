# FileIO.toPath

Create a sink which will write incoming `ByteString` s to a given file path.

@ref[File IO Sinks and Sources](../index.md#file-io-sinks-and-sources)

## Signature

@apidoc[FileIO.toPath](FileIO$) { scala="#toPath(f:java.nio.file.Path,options:Set[java.nio.file.OpenOption],startPosition:Long):akka.stream.scaladsl.Sink[akka.util.ByteString,scala.concurrent.Future[akka.stream.IOResult]]" java="#toPath(java.nio.file.Path,java.util.Set,long)" }


## Description

Creates a Sink which writes incoming `ByteString` elements to the given file path. Overwrites existing files by truncating their contents as default. 
Materializes a @scala[`Future`] @java[`CompletionStage`] of `IOResult` that will be completed with the size of the file (in bytes) at the streams completion, and a possible exception if IO operation was not completed successfully.

## Example

Scala
:  @@snip [StreamFileDocSpec.scala](/akka-docs/src/test/scala/docs/stream/io/StreamFileDocSpec.scala) { #file-sink }

Java
:  @@snip [StreamFileDocTest.java](/akka-docs/src/test/java/jdocs/stream/io/StreamFileDocTest.java) { #file-sink }

# FileIO.fromPath

Emits the contents of a file from the given path.

@ref[File IO Sinks and Sources](../index.md#file-io-sinks-and-sources)

## Signature

@apidoc[FileIO.fromPath](FileIO$) { scala="#fromPath(f:java.nio.file.Path,chunkSize:Int,startPosition:Long):akka.stream.scaladsl.Source[akka.util.ByteString,scala.concurrent.Future[akka.stream.IOResult]]" java="#fromPath(java.nio.file.Path,int,long)" }


## Description

Emits the contents of a file from the given path, as `ByteString`s, materializes into a @scala[`Future`] @java[`CompletionStage`] which will be completed with
a `IOResult` upon reaching the end of the file or if there is a failure.

## Example

Scala
:  @@snip [StreamFileDocSpec.scala](/akka-docs/src/test/scala/docs/stream/io/StreamFileDocSpec.scala) { #file-source }

Java
:  @@snip [StreamFileDocTest.java](/akka-docs/src/test/java/jdocs/stream/io/StreamFileDocTest.java) { #file-source }


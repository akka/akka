/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import java.io.File
import java.nio.file.StandardOpenOption
import java.nio.file.StandardOpenOption._

import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl.io._
import akka.stream.IOResult
import akka.util.ByteString

import scala.concurrent.Future

/**
 * Java API: Factories to create sinks and sources from files
 */
object FileIO {

  import Sink.{ shape ⇒ sinkShape }
  import Source.{ shape ⇒ sourceShape }

  /**
   * Creates a Source from a Files contents.
   * Emitted elements are `chunkSize` sized [[akka.util.ByteString]] elements,
   * except the final element, which will be up to `chunkSize` in size.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * It materializes a [[Future]] of [[IOResult]] containing the number of bytes read from the source file upon completion,
   * and a possible exception if IO operation was not completed successfully.
   *
   * @param f         the File to read from
   * @param chunkSize the size of each read operation, defaults to 8192
   */
  def fromFile(f: File, chunkSize: Int = 8192): Source[ByteString, Future[IOResult]] =
    new Source(new FileSource(f, chunkSize, DefaultAttributes.fileSource, sourceShape("FileSource")))

  /**
   * Creates a Sink which writes incoming [[ByteString]] elements to the given file. Overwrites existing files by default.
   *
   * Materializes a [[Future]] of [[IOResult]] that will be completed with the size of the file (in bytes) at the streams completion,
   * and a possible exception if IO operation was not completed successfully.
   *
   * This source is backed by an Actor which will use the dedicated `akka.stream.blocking-io-dispatcher`,
   * unless configured otherwise by using [[ActorAttributes]].
   *
   * @param f the File to write to
   * @param options File open options, defaults to Set(WRITE, CREATE)
   */
  def toFile(f: File, options: Set[StandardOpenOption] = Set(WRITE, CREATE)): Sink[ByteString, Future[IOResult]] =
    new Sink(new FileSink(f, options, DefaultAttributes.fileSink, sinkShape("FileSink")))
}

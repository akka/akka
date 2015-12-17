/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import java.io.{ OutputStream, InputStream, File }

import akka.stream.ActorAttributes
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl.io._
import akka.util.ByteString

import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * Java API: Factories to create sinks and sources from files
 */
object FileIO {

  import Source.{ shape ⇒ sourceShape }
  import Sink.{ shape ⇒ sinkShape }

  /**
   * Creates a Source from a Files contents.
   * Emitted elements are `chunkSize` sized [[akka.util.ByteString]] elements,
   * except the final element, which will be up to `chunkSize` in size.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * It materializes a [[Future]] containing the number of bytes read from the source file upon completion.
   *
   * @param f the File to read from
   * @param chunkSize the size of each read operation, defaults to 8192
   */
  def fromFile(f: File, chunkSize: Int = 8192): Source[ByteString, Future[Long]] =
    new Source(new FileSource(f, chunkSize, DefaultAttributes.fileSource, sourceShape("FileSource")))

  /**
   * Creates a Sink which writes incoming [[ByteString]] elements to the given file and either overwrites
   * or appends to it.
   *
   * Materializes a [[Future]] that will be completed with the size of the file (in bytes) at the streams completion.
   *
   * This source is backed by an Actor which will use the dedicated `akka.stream.blocking-io-dispatcher`,
   * unless configured otherwise by using [[ActorAttributes]].
   */
  def toFile(f: File, append: Boolean = false): Sink[ByteString, Future[Long]] =
    new Sink(new FileSink(f, append, DefaultAttributes.fileSink, sinkShape("FileSink")))

}
/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import java.io.{ InputStream, OutputStream, File }

import akka.japi.function
import akka.stream.{ scaladsl, javadsl, ActorAttributes }
import akka.util.ByteString

import scala.concurrent.Future

/**
 * Factories to create sinks and sources from files
 */
object FileIO {

  /**
   * Creates a Sink that writes incoming [[ByteString]] elements to the given file.
   * Overwrites existing files, if you want to append to an existing file use [[#file(File, Boolean)]] and
   * pass in `true` as the Boolean argument.
   *
   * Materializes a [[Future]] that will be completed with the size of the file (in bytes) at the streams completion.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * @param f The file to write to
   */
  def toFile(f: File): javadsl.Sink[ByteString, Future[java.lang.Long]] = toFile(f, append = false)

  /**
   * Creates a Sink that writes incoming [[ByteString]] elements to the given file and either overwrites
   * or appends to it.
   *
   * Materializes a [[Future]] that will be completed with the size of the file (in bytes) at the streams completion.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * @param f The file to write to
   * @param append Whether or not the file should be overwritten or appended to
   */
  def toFile(f: File, append: Boolean): javadsl.Sink[ByteString, Future[java.lang.Long]] =
    new Sink(scaladsl.FileIO.toFile(f, append)).asInstanceOf[javadsl.Sink[ByteString, Future[java.lang.Long]]]

  /**
   * Creates a Source from a Files contents.
   * Emitted elements are [[ByteString]] elements, chunked by default by 8192 bytes,
   * except the last element, which will be up to 8192 in size.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * It materializes a [[Future]] containing the number of bytes read from the source file upon completion.
   */
  def fromFile(f: File): javadsl.Source[ByteString, Future[java.lang.Long]] = fromFile(f, 8192)

  /**
   * Creates a synchronous (Java 6 compatible) Source from a Files contents.
   * Emitted elements are `chunkSize` sized [[ByteString]] elements,
   * except the last element, which will be up to `chunkSize` in size.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * It materializes a [[Future]] containing the number of bytes read from the source file upon completion.
   */
  def fromFile(f: File, chunkSize: Int): javadsl.Source[ByteString, Future[java.lang.Long]] =
    new Source(scaladsl.FileIO.fromFile(f, chunkSize)).asInstanceOf[Source[ByteString, Future[java.lang.Long]]]

}

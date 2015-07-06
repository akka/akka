/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import java.io.File
import akka.stream.scaladsl.Source
import akka.stream.{ ActorAttributes, Attributes, javadsl }
import akka.util.ByteString
import scala.concurrent.Future

object SynchronousFileSource {
  import akka.stream.impl.io.SynchronousFileSource
  final val DefaultChunkSize = 8192
  final val DefaultAttributes = Attributes.name("synchronousFileSource")

  /**
   * Creates a synchronous (Java 6 compatible) Source from a Files contents.
   * Emitted elements are `chunkSize` sized [[ByteString]] elements.
   *
   * This source is backed by an Actor which will use the dedicated thread-pool base dispatcher.
   * You can configure the default dispatcher for this Source by changing the `akka.stream.file-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * It materializes a [[Future]] containing the number of bytes read from the source file upon completion.
   */
  def apply(f: File, chunkSize: Int = DefaultChunkSize): Source[ByteString, Future[Long]] =
    new Source(new SynchronousFileSource(f, chunkSize, DefaultAttributes, Source.shape("SynchronousFileSource")).nest()) // TO DISCUSS: I had to add wrap() here to make the name available

  /**
   * Creates a synchronous (Java 6 compatible) Source from a Files contents.
   * Emitted elements are [[ByteString]] elements, chunked by default by [[DefaultChunkSize]] bytes.
   *
   * This source is backed by an Actor which will use the dedicated thread-pool base dispatcher.
   * You can configure the default dispatcher for this Source by changing the `akka.stream.file-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * It materializes a [[Future]] containing the number of bytes read from the source file upon completion.
   */
  def create(f: File): javadsl.Source[ByteString, Future[java.lang.Long]] = create(f, DefaultChunkSize)

  /**
   * Creates a synchronous (Java 6 compatible) Source from a Files contents.
   * Emitted elements are `chunkSize` sized [[ByteString]] elements.
   *
   * This source is backed by an Actor which will use the dedicated thread-pool base dispatcher.
   * You can configure the default dispatcher for this Source by changing the `akka.stream.file-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * It materializes a [[Future]] containing the number of bytes read from the source file upon completion.
   */
  def create(f: File, chunkSize: Int): javadsl.Source[ByteString, Future[java.lang.Long]] =
    apply(f, chunkSize).asJava.asInstanceOf[javadsl.Source[ByteString, Future[java.lang.Long]]]
}

/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import java.io.{ InputStream, OutputStream }

import akka.japi.function
import akka.stream.{ scaladsl, javadsl, ActorAttributes }
import akka.util.ByteString

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

/**
 * Converters for interacting with the blocking `java.io` streams APIs
 */
object StreamConverters {
  /**
   * Sink which writes incoming [[ByteString]]s to an [[OutputStream]] created by the given function.
   *
   * Materializes a [[Future]] that will be completed with the size of the file (in bytes) at the streams completion.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * @param f A Creator which creates an OutputStream to write to
   */
  def fromOutputStream(f: function.Creator[OutputStream]): javadsl.Sink[ByteString, Future[java.lang.Long]] =
    new Sink(scaladsl.StreamConverters.fromOutputStream(() ⇒ f.create())).asInstanceOf[javadsl.Sink[ByteString, Future[java.lang.Long]]]

  /**
   * Creates a Sink which when materialized will return an [[java.io.InputStream]] which it is possible
   * to read the values produced by the stream this Sink is attached to.
   *
   * This method uses a default read timeout, use [[#inputStream(FiniteDuration)]] to explicitly
   * configure the timeout.
   *
   * This Sink is intended for inter-operation with legacy APIs since it is inherently blocking.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   */
  def asInputStream(): Sink[ByteString, InputStream] = new Sink(scaladsl.StreamConverters.asInputStream())

  /**
   * Creates a Sink which when materialized will return an [[java.io.InputStream]] which it is possible
   * to read the values produced by the stream this Sink is attached to.
   *
   * This Sink is intended for inter-operation with legacy APIs since it is inherently blocking.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * @param readTimeout the max time the read operation on the materialized InputStream should block
   */
  def asInputStream(readTimeout: FiniteDuration): Sink[ByteString, InputStream] =
    new Sink(scaladsl.StreamConverters.asInputStream(readTimeout))

  /**
   * Creates a Source from an [[java.io.InputStream]] created by the given function.
   * Emitted elements are `chunkSize` sized [[akka.util.ByteString]] elements,
   * except the final element, which will be up to `chunkSize` in size.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * It materializes a [[Future]] containing the number of bytes read from the source file upon completion.
   */
  def fromInputStream(in: function.Creator[InputStream], chunkSize: Int): javadsl.Source[ByteString, Future[java.lang.Long]] =
    new Source(scaladsl.StreamConverters.fromInputStream(() ⇒ in.create(), chunkSize)).asInstanceOf[Source[ByteString, Future[java.lang.Long]]]

  /**
   * Creates a Source from an [[java.io.InputStream]] created by the given function.
   * Emitted elements are [[ByteString]] elements, chunked by default by 8192 bytes,
   * except the last element, which will be up to 8192 in size.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * It materializes a [[Future]] containing the number of bytes read from the source file upon completion.
   */
  def fromInputStream(in: function.Creator[InputStream]): javadsl.Source[ByteString, Future[java.lang.Long]] = fromInputStream(in, 8192)

  /**
   * Creates a Source which when materialized will return an [[java.io.OutputStream]] which it is possible
   * to write the ByteStrings to the stream this Source is attached to.
   *
   * This Source is intended for inter-operation with legacy APIs since it is inherently blocking.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * @param writeTimeout the max time the write operation on the materialized OutputStream should block
   */
  def asOutputStream(writeTimeout: FiniteDuration): javadsl.Source[ByteString, OutputStream] =
    new Source(scaladsl.StreamConverters.asOutputStream(writeTimeout))

  /**
   * Creates a Source which when materialized will return an [[java.io.OutputStream]] which it is possible
   * to write the ByteStrings to the stream this Source is attached to. The write timeout for OutputStreams
   * materialized will default to 5 seconds, @see [[#outputStream(FiniteDuration)]] if you want to override it.
   *
   * This Source is intended for inter-operation with legacy APIs since it is inherently blocking.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   */
  def asOutputStream(): javadsl.Source[ByteString, OutputStream] =
    new Source(scaladsl.StreamConverters.asOutputStream())

}

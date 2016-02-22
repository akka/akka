/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import java.io.{ OutputStream, InputStream }

import akka.stream.IOResult
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl.io.{ InputStreamSinkStage, OutputStreamSink, OutputStreamSourceStage, InputStreamSource }
import akka.util.ByteString

import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * Converters for interacting with the blocking `java.io` streams APIs
 */
object StreamConverters {

  import Source.{ shape ⇒ sourceShape }
  import Sink.{ shape ⇒ sinkShape }

  /**
   * Creates a Source from an [[InputStream]] created by the given function.
   * Emitted elements are `chunkSize` sized [[akka.util.ByteString]] elements,
   * except the final element, which will be up to `chunkSize` in size.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * It materializes a [[Future]] of [[IOResult]] containing the number of bytes read from the source file upon completion,
   * and a possible exception if IO operation was not completed successfully.
   *
   * The created [[InputStream]] will be closed when the [[Source]] is cancelled.
   *
   * @param in a function which creates the InputStream to read from
   * @param chunkSize the size of each read operation, defaults to 8192
   */
  def fromInputStream(in: () ⇒ InputStream, chunkSize: Int = 8192): Source[ByteString, Future[IOResult]] =
    new Source(new InputStreamSource(in, chunkSize, DefaultAttributes.inputStreamSource, sourceShape("InputStreamSource")))

  /**
   * Creates a Source which when materialized will return an [[OutputStream]] which it is possible
   * to write the ByteStrings to the stream this Source is attached to.
   *
   * This Source is intended for inter-operation with legacy APIs since it is inherently blocking.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * The created [[OutputStream]] will be closed when the [[Source]] is cancelled, and closing the [[OutputStream]]
   * will complete this [[Source]].
   *
   * @param writeTimeout the max time the write operation on the materialized OutputStream should block, defaults to 5 seconds
   */
  def asOutputStream(writeTimeout: FiniteDuration = 5.seconds): Source[ByteString, OutputStream] =
    Source.fromGraph(new OutputStreamSourceStage(writeTimeout))

  /**
   * Creates a Sink which writes incoming [[ByteString]]s to an [[OutputStream]] created by the given function.
   *
   * Materializes a [[Future]] of [[IOResult]] that will be completed with the size of the file (in bytes) at the streams completion,
   * and a possible exception if IO operation was not completed successfully.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   * If `autoFlush` is true the OutputStream will be flushed whenever a byte array is written, defaults to false.
   *
   * The [[OutputStream]] will be closed when the stream flowing into this [[Sink]] is completed. The [[Sink]]
   * will cancel the stream when the [[OutputStream]] is no longer writable.
   */
  def fromOutputStream(out: () ⇒ OutputStream, autoFlush: Boolean = false): Sink[ByteString, Future[IOResult]] =
    new Sink(new OutputStreamSink(out, DefaultAttributes.outputStreamSink, sinkShape("OutputStreamSink"), autoFlush))

  /**
   * Creates a Sink which when materialized will return an [[InputStream]] which it is possible
   * to read the values produced by the stream this Sink is attached to.
   *
   * This Sink is intended for inter-operation with legacy APIs since it is inherently blocking.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * The [[InputStream]] will be closed when the stream flowing into this [[Sink]] completes, and
   * closing the [[InputStream]] will cancel this [[Sink]].
   *
   * @param readTimeout the max time the read operation on the materialized InputStream should block
   */
  def asInputStream(readTimeout: FiniteDuration = 5.seconds): Sink[ByteString, InputStream] =
    Sink.fromGraph(new InputStreamSinkStage(readTimeout))

}

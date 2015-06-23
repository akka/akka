/**
 * Copyright (C) 2014-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import java.io.OutputStream

import akka.japi.function.Creator
import akka.stream.impl.io.OutputStreamSink
import akka.stream.scaladsl.Sink
import akka.stream.{ ActorAttributes, Attributes, javadsl }
import akka.util.ByteString

import scala.concurrent.Future

/**
 * Sink which writes incoming [[ByteString]]s to the given [[OutputStream]].
 */
object OutputStreamSink {

  final val DefaultAttributes = Attributes.name("outputStreamSink")

  /**
   * Sink which writes incoming [[ByteString]]s to the given [[OutputStream]].
   *
   * Materializes a [[Future]] that will be completed with the size of the file (in bytes) at the streams completion.
   *
   * This source is backed by an Actor which will use the dedicated `akka.stream.file-io-dispatcher`,
   * unless configured otherwise by using [[ActorAttributes]].
   */
  def apply(output: () ⇒ OutputStream): Sink[ByteString, Future[Long]] =
    new Sink(new OutputStreamSink(output, DefaultAttributes, Sink.shape("OutputStreamSink")))

  /**
   * Java API
   *
   * Sink which writes incoming [[ByteString]]s to the given [[OutputStream]].
   *
   * Materializes a [[Future]] that will be completed with the size of the file (in bytes) at the streams completion.
   */
  def create(f: Creator[OutputStream]): javadsl.Sink[ByteString, Future[java.lang.Long]] =
    apply(() ⇒ f.create()).asJava.asInstanceOf[javadsl.Sink[ByteString, Future[java.lang.Long]]]

}

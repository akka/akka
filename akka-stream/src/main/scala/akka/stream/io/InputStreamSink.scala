/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import java.io.InputStream
import java.lang.{ Long ⇒ JLong }

import akka.stream.impl.io.InputStreamSinkStage
import akka.stream.scaladsl.Sink
import akka.stream.{ Attributes, ActorAttributes, javadsl }
import akka.util.ByteString

import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.language.postfixOps

/**
 * Sink which allows to use [[java.io.InputStream]] to interact with reactive stream.
 */
object InputStreamSink {

  /**
   * Creates a synchronous (Java 6 compatible) Sink
   *
   * It materializes an [[java.io.InputStream]] to interacting with reactive stream.
   *
   * This sink is backed by an Actor which will use the dedicated `akka.stream.default-blocking-io-dispatcher`,
   * unless configured otherwise by using [[ActorAttributes]].
   */
  def apply(timeout: FiniteDuration = 5 seconds): Sink[ByteString, InputStream] =
    Sink.fromGraph(new InputStreamSinkStage(timeout))
      .withAttributes(ActorAttributes.dispatcher("akka.stream.default-blocking-io-dispatcher") and
        Attributes.name("InputStreamSink"))

  /**
   * Creates a synchronous (Java 6 compatible) Sink
   *
   * It materializes an [[java.io.InputStream]] to interacting with reactive stream.
   *
   * This sink is backed by an Actor which will use the dedicated `akka.stream.default-blocking-io-dispatcher`,
   * unless configured otherwise by using [[akka.stream.ActorAttributes]].
   */
  def create(): javadsl.Sink[ByteString, InputStream] =
    new javadsl.Sink(apply())

  /**
   * Creates a synchronous (Java 6 compatible) Sink
   *
   * It materializes an [[java.io.InputStream]] to interacting with reactive stream.
   *
   * This sink is backed by an Actor which will use the dedicated `akka.stream.default-blocking-io-dispatcher`,
   * unless configured otherwise by using [[akka.stream.ActorAttributes]].
   */
  def create(timeout: FiniteDuration): javadsl.Sink[ByteString, InputStream] =
    new javadsl.Sink(apply(timeout))

}

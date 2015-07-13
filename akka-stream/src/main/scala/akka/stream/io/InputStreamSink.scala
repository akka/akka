/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import java.io.InputStream

import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl.io.{ JavaInputStreamSink, InputStreamSink }
import akka.stream.scaladsl.Sink
import akka.stream.{ ActorAttributes, Attributes, javadsl }
import akka.util.ByteString

import scala.concurrent.Future
import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.language.postfixOps
import java.lang.{ Long ⇒ JLong }

import akka.japi

/**
 * Sink which allows to use [[InputStream]] to interact with reactive stream.
 */
object InputStreamSink {

  /**
   * Creates a synchronous (Java 6 compatible) Sink
   *
   * It materializes a [[Future]] containing the number of bytes written to InputStream upon completion and
   * [[InputStream]] to interacting with reactive stream.
   *
   * This sink is backed by an Actor which will use the dedicated `akka.stream.blocking-io-dispatcher`,
   * unless configured otherwise by using [[ActorAttributes]].
   */
  def apply(timeout: FiniteDuration = 5 seconds): Sink[ByteString, (InputStream, Future[Long])] = {
    new Sink(new InputStreamSink(timeout, DefaultAttributes.inputStreamSink, Sink.shape("InputStreamSink")))
  }

  /**
   * Creates a synchronous (Java 6 compatible) Sink
   *
   * It materializes a [[Future]] containing the number of bytes written to InputStream upon completion and
   * [[InputStream]] to interacting with reactive stream.
   *
   * This sink is backed by an Actor which will use the dedicated `akka.stream.blocking-io-dispatcher`,
   * unless configured otherwise by using [[ActorAttributes]].
   */
  def create(): javadsl.Sink[ByteString, japi.Pair[InputStream, Future[JLong]]] =
    createInternal()

  /**
   * Creates a synchronous (Java 6 compatible) Sink
   *
   * It materializes a [[Future]] containing the number of bytes written to InputStream upon completion and
   * [[InputStream]] to interacting with reactive stream.
   *
   * This sink is backed by an Actor which will use the dedicated `akka.stream.blocking-io-dispatcher`,
   * unless configured otherwise by using [[ActorAttributes]].
   */
  def create(timeout: FiniteDuration): javadsl.Sink[ByteString, japi.Pair[InputStream, Future[JLong]]] =
    createInternal(timeout)

  private def createInternal(timeout: FiniteDuration = 5 seconds): javadsl.Sink[ByteString, japi.Pair[InputStream, Future[JLong]]] =
    new Sink(new JavaInputStreamSink(timeout, DefaultAttributes.inputStreamSink, Sink.shape("InputStreamSink"))).asJava

}

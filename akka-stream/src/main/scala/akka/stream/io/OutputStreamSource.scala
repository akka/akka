/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import java.io.OutputStream
import java.lang.{ Long ⇒ JLong }

import akka.japi
import akka.stream._
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl.io.{ OutputStreamSource, JavaOutputStreamSource }
import akka.stream.scaladsl.Source
import akka.util.ByteString

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.implicitConversions

/**
 * Source which allows to use [[OutputStream]] to interact with reactive stream.
 */
object OutputStreamSource {
  import scala.language.postfixOps

  /**
   * Creates a synchronous (Java 6 compatible) Source.
   *
   * It materializes a [[Future]] containing the number of bytes written to OutputStream upon completion and
   * [[OutputStream]] to interacting with reactive stream.
   *
   * This source is backed by an Actor which will use the dedicated `akka.stream.blocking-io-dispatcher`,
   * unless configured otherwise by using [[ActorAttributes]].
   */
  def apply(timeout: FiniteDuration = 5.seconds): Source[ByteString, (OutputStream, Future[Long])] = {
    new Source(new OutputStreamSource(timeout, DefaultAttributes.outputStreamSource, Source.shape("OutputStreamSource")))
  }

  /**
   * Creates a synchronous (Java 6 compatible) Source.
   *
   * It materializes a [[Future]] containing the number of bytes written to OutputStream upon completion and
   * [[OutputStream]] to interacting with reactive stream.
   *
   * This source is backed by an Actor which will use the dedicated `akka.stream.blocking-io-dispatcher`,
   * unless configured otherwise by using [[ActorAttributes]].
   */
  def create(): javadsl.Source[ByteString, japi.Pair[OutputStream, Future[JLong]]] =
    createInternal()

  /**
   * Creates a synchronous (Java 6 compatible) Source.
   *
   * It materializes a [[Future]] containing the number of bytes written to OutputStream upon completion and
   * [[OutputStream]] to interacting with reactive stream.
   *
   * This source is backed by an Actor which will use the dedicated `akka.stream.blocking-io-dispatcher`,
   * unless configured otherwise by using [[ActorAttributes]].
   */
  def create(timeout: FiniteDuration): javadsl.Source[ByteString, japi.Pair[OutputStream, Future[JLong]]] = {
    createInternal(timeout)
  }

  private def createInternal(timeout: FiniteDuration = 5 seconds): javadsl.Source[ByteString, japi.Pair[OutputStream, Future[JLong]]] =
    new Source(new JavaOutputStreamSource(timeout, DefaultAttributes.outputStreamSource, Source.shape("OutputStreamSource"))).asJava

}

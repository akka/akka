/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import java.io.OutputStream

import akka.stream.Attributes.Name
import akka.stream._
import akka.stream.impl.io.OutputStreamSourceStage
import akka.stream.scaladsl.{ FlowGraph, Source }
import akka.util.ByteString

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.implicitConversions

/**
 * Source which allows to use [[java.io.OutputStream]] to interact with reactive stream.
 */
object OutputStreamSource {
  import scala.language.postfixOps

  /**
   * Creates a synchronous (Java 6 compatible) Source.
   *
   * It materializes an [[java.io.OutputStream]] to interact with reactive stream.
   *
   * This source is backed by an Actor which will use the dedicated `akka.stream.default-blocking-io-dispatcher`,
   * unless configured otherwise by using [[akka.stream.ActorAttributes]].
   */
  def apply(timeout: FiniteDuration = 5.seconds): Source[ByteString, OutputStream] =
    Source.fromGraph(new OutputStreamSourceStage(timeout))
      .withAttributes(ActorAttributes.dispatcher("akka.stream.default-blocking-io-dispatcher") and
        Attributes.name("OutputStreamSource"))

  /**
   * Creates a synchronous (Java 6 compatible) Source.
   *
   * It materializes an [[java.io.OutputStream]] to interact with reactive stream.
   *
   * This source is backed by an Actor which will use the dedicated `akka.stream.default-blocking-io-dispatcher`,
   * unless configured otherwise by using [[akka.stream.ActorAttributes]].
   */
  def create(): javadsl.Source[ByteString, OutputStream] =
    new javadsl.Source(apply())

  /**
   * Creates a synchronous (Java 6 compatible) Source.
   *
   * It materializes an [[java.io.OutputStream]] to interacting with reactive stream.
   *
   * This source is backed by an Actor which will use the dedicated `akka.stream.default-blocking-io-dispatcher`,
   * unless configured otherwise by using [[akka.stream.ActorAttributes]].
   */
  def create(timeout: FiniteDuration): javadsl.Source[ByteString, OutputStream] = {
    new javadsl.Source(apply(timeout))
  }

}

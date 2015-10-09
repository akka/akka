/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.ws

import akka.actor.ActorSystem
import akka.stream.scaladsl.{ Source, Sink }
import akka.stream.testkit.TestSubscriber
import akka.util.ByteString

import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration

trait ByteStringSinkProbe {
  def sink: Sink[ByteString, Unit]

  def expectBytes(length: Int): ByteString
  def expectBytes(expected: ByteString): Unit

  def expectUtf8EncodedString(string: String): Unit

  def expectNoBytes(): Unit
  def expectNoBytes(timeout: FiniteDuration): Unit

  def expectComplete(): Unit
  def expectError(): Throwable
  def expectError(cause: Throwable): Unit
}

object ByteStringSinkProbe {
  def apply()(implicit system: ActorSystem): ByteStringSinkProbe =
    new ByteStringSinkProbe {
      val probe = TestSubscriber.probe[ByteString]()
      val sink: Sink[ByteString, Unit] = Sink(probe)

      def expectNoBytes(): Unit = {
        probe.ensureSubscription()
        probe.expectNoMsg()
      }
      def expectNoBytes(timeout: FiniteDuration): Unit = {
        probe.ensureSubscription()
        probe.expectNoMsg(timeout)
      }

      var inBuffer = ByteString.empty
      @tailrec def expectBytes(length: Int): ByteString =
        if (inBuffer.size >= length) {
          val res = inBuffer.take(length)
          inBuffer = inBuffer.drop(length)
          res
        } else {
          inBuffer ++= probe.requestNext()
          expectBytes(length)
        }

      def expectBytes(expected: ByteString): Unit =
        assert(expectBytes(expected.length) == expected, "expected ")

      def expectUtf8EncodedString(string: String): Unit =
        expectBytes(ByteString(string, "utf8"))

      def expectComplete(): Unit = probe.expectComplete()
      def expectError(): Throwable = probe.expectError()
      def expectError(cause: Throwable): Unit = probe.expectError(cause)
    }
}

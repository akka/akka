/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import akka.stream.testkit.TestSubscriber
import akka.util.ByteString

import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration

trait ByteStringSinkProbe {
  def sink: Sink[ByteString, NotUsed]

  def expectBytes(length: Int): ByteString
  def expectBytes(expected: ByteString): Unit

  def expectUtf8EncodedString(string: String): Unit

  def expectNoBytes(): Unit
  def expectNoBytes(timeout: FiniteDuration): Unit

  def expectSubscriptionAndComplete(): Unit
  def expectComplete(): Unit
  def expectError(): Throwable
  def expectError(cause: Throwable): Unit

  def request(n: Long): Unit
}

object ByteStringSinkProbe {
  def apply()(implicit system: ActorSystem): ByteStringSinkProbe =
    new ByteStringSinkProbe {
      val probe = TestSubscriber.probe[ByteString]()
      val sink: Sink[ByteString, NotUsed] = Sink.fromSubscriber(probe)

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

      def expectSubscriptionAndComplete(): Unit = probe.expectSubscriptionAndComplete()
      def expectComplete(): Unit = probe.expectComplete()
      def expectError(): Throwable = probe.expectError()
      def expectError(cause: Throwable): Unit = probe.expectError(cause)

      def request(n: Long): Unit = probe.request(n)
    }
}

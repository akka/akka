/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.testkit

import akka.NotUsed

import scala.concurrent.duration._

import akka.util.ByteString

import akka.actor.ActorSystem

import akka.stream.Materializer
import akka.stream.scaladsl.{ Keep, Source, Sink, Flow }
import akka.stream.testkit.{ TestPublisher, TestSubscriber }

import akka.http.impl.util._
import akka.http.scaladsl.model.ws.{ BinaryMessage, TextMessage, Message }

/**
 * A WSProbe is a probe that implements a `Flow[Message, Message, Unit]` for testing
 * websocket code.
 *
 * Requesting elements is handled automatically.
 */
trait WSProbe {
  def flow: Flow[Message, Message, NotUsed]

  /**
   * Send the given messages out of the flow.
   */
  def sendMessage(message: Message): Unit

  /**
   * Send a text message containing the given string out of the flow.
   */
  def sendMessage(text: String): Unit

  /**
   * Send a binary message containing the given bytes out of the flow.
   */
  def sendMessage(bytes: ByteString): Unit

  /**
   * Complete the output side of the flow.
   */
  def sendCompletion(): Unit

  /**
   * Expect a message on the input side of the flow.
   */
  def expectMessage(): Message

  /**
   * Expect a text message on the input side of the flow and compares its payload with the given one.
   * If the received message is streamed its contents are collected and then asserted against the given
   * String.
   */
  def expectMessage(text: String): Unit

  /**
   * Expect a binary message on the input side of the flow and compares its payload with the given one.
   * If the received message is streamed its contents are collected and then asserted against the given
   * ByteString.
   */
  def expectMessage(bytes: ByteString): Unit

  /**
   * Expect no message on the input side of the flow.
   */
  def expectNoMessage(): Unit

  /**
   * Expect no message on the input side of the flow for the given maximum duration.
   */
  def expectNoMessage(max: FiniteDuration): Unit

  /**
   * Expect completion on the input side of the flow.
   */
  def expectCompletion(): Unit

  /**
   * The underlying probe for the ingoing side of this probe. Can be used if the methods
   * on WSProbe don't allow fine enough control over the message flow.
   */
  def inProbe: TestSubscriber.Probe[Message]

  /**
   * The underlying probe for the outgoing side of this probe. Can be used if the methods
   * on WSProbe don't allow fine enough control over the message flow.
   */
  def outProbe: TestPublisher.Probe[Message]
}

object WSProbe {
  /**
   * Creates a WSProbe to use in tests against websocket handlers.
   *
   * @param maxChunks The maximum number of chunks to collect for streamed messages.
   * @param maxChunkCollectionMills The maximum time in milliseconds to collect chunks for streamed messages.
   */
  def apply(maxChunks: Int = 1000, maxChunkCollectionMills: Long = 5000)(implicit system: ActorSystem, materializer: Materializer): WSProbe =
    new WSProbe {
      val subscriber = TestSubscriber.probe[Message]()
      val publisher = TestPublisher.probe[Message]()

      def flow: Flow[Message, Message, NotUsed] = Flow.fromSinkAndSourceMat(Sink.fromSubscriber(subscriber), Source.fromPublisher(publisher))(Keep.none)

      def sendMessage(message: Message): Unit = publisher.sendNext(message)
      def sendMessage(text: String): Unit = sendMessage(TextMessage(text))
      def sendMessage(bytes: ByteString): Unit = sendMessage(BinaryMessage(bytes))
      def sendCompletion(): Unit = publisher.sendComplete()

      def expectMessage(): Message = subscriber.requestNext()
      def expectMessage(text: String): Unit = expectMessage() match {
        case t: TextMessage ⇒
          val collectedMessage = collect(t.textStream)(_ + _)
          assert(collectedMessage == text, s"""Expected TextMessage("$text") but got TextMessage("$collectedMessage")""")
        case _ ⇒ throw new AssertionError(s"""Expected TextMessage("$text") but got BinaryMessage""")
      }
      def expectMessage(bytes: ByteString): Unit = expectMessage() match {
        case t: BinaryMessage ⇒
          val collectedMessage = collect(t.dataStream)(_ ++ _)
          assert(collectedMessage == bytes, s"""Expected BinaryMessage("$bytes") but got BinaryMessage("$collectedMessage")""")
        case _ ⇒ throw new AssertionError(s"""Expected BinaryMessage("$bytes") but got TextMessage""")
      }

      def expectNoMessage(): Unit = subscriber.expectNoMsg()
      def expectNoMessage(max: FiniteDuration): Unit = subscriber.expectNoMsg(max)

      def expectCompletion(): Unit = subscriber.expectComplete()

      def inProbe: TestSubscriber.Probe[Message] = subscriber
      def outProbe: TestPublisher.Probe[Message] = publisher

      private def collect[T](stream: Source[T, Any])(reduce: (T, T) ⇒ T): T =
        stream.grouped(maxChunks)
          .runWith(Sink.head)
          .awaitResult(maxChunkCollectionMills.millis)
          .reduce(reduce)
    }
}

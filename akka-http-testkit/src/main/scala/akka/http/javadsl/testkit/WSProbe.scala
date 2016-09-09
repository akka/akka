/*
 * Copyright (C) 2016-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.testkit

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.javadsl.model.ws.Message
import akka.stream.Materializer
import akka.stream.javadsl.Flow
import akka.stream.scaladsl
import akka.util.ByteString

import akka.http.scaladsl.{ testkit ⇒ st }

import akka.http.impl.util.JavaMapping.Implicits._

import scala.concurrent.duration._

/**
 * A WSProbe is a probe that implements a `Flow[Message, Message, Unit]` for testing
 * websocket code.
 *
 * Requesting elements is handled automatically.
 */
class WSProbe(delegate: st.WSProbe) {

  def flow: Flow[Message, Message, Any] = {
    val underlying = scaladsl.Flow[Message].map(_.asScala).via(delegate.flow).map(_.asJava)
    new Flow[Message, Message, NotUsed](underlying)
  }

  /**
   * Send the given messages out of the flow.
   */
  def sendMessage(message: Message): Unit = delegate.sendMessage(message.asScala)

  /**
   * Send a text message containing the given string out of the flow.
   */
  def sendMessage(text: String): Unit = delegate.sendMessage(text)

  /**
   * Send a binary message containing the given bytes out of the flow.
   */
  def sendMessage(bytes: ByteString): Unit = delegate.sendMessage(bytes)

  /**
   * Complete the output side of the flow.
   */
  def sendCompletion(): Unit = delegate.sendCompletion()

  /**
   * Expect a message on the input side of the flow.
   */
  def expectMessage(): Message = delegate.expectMessage()

  /**
   * Expect a text message on the input side of the flow and compares its payload with the given one.
   * If the received message is streamed its contents are collected and then asserted against the given
   * String.
   */
  def expectMessage(text: String): Unit = delegate.expectMessage(text)

  /**
   * Expect a binary message on the input side of the flow and compares its payload with the given one.
   * If the received message is streamed its contents are collected and then asserted against the given
   * ByteString.
   */
  def expectMessage(bytes: ByteString): Unit = delegate.expectMessage(bytes)

  /**
   * Expect no message on the input side of the flow.
   */
  def expectNoMessage(): Unit = delegate.expectNoMessage()

  /**
   * Expect no message on the input side of the flow for the given maximum duration.
   */
  def expectNoMessage(max: FiniteDuration): Unit = delegate.expectNoMessage(max)

  /**
   * Expect completion on the input side of the flow.
   */
  def expectCompletion(): Unit = delegate.expectCompletion()

}

object WSProbe {

  // A convenient method to create WSProbe with default maxChunks and maxChunkCollectionMills
  def create(system: ActorSystem, materializer: Materializer): WSProbe = {
    create(system, materializer, 1000, 5000)
  }

  /**
   * Creates a WSProbe to use in tests against websocket handlers.
   *
   * @param maxChunks The maximum number of chunks to collect for streamed messages.
   * @param maxChunkCollectionMills The maximum time in milliseconds to collect chunks for streamed messages.
   */
  def create(system: ActorSystem, materializer: Materializer, maxChunks: Int, maxChunkCollectionMills: Long): WSProbe = {
    val delegate = st.WSProbe(maxChunks, maxChunkCollectionMills)(system, materializer)
    new WSProbe(delegate)
  }

}

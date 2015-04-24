/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model.ws

import akka.http.model.ws
import akka.stream.javadsl.Source
import akka.util.ByteString

/**
 * Represents a Websocket message. A message can either be a binary message or a text message.
 */
sealed abstract class Message {
  /**
   * Is this message a text message? If true, [[asTextMessage]] will return this
   * text message, if false, [[asBinaryMessage]] will return this binary message.
   */
  def isText: Boolean

  /**
   * Returns this TextMessage if it is a text message, throws otherwise.
   */
  def asTextMessage: TextMessage

  /**
   * Returns this BinaryMessage if it is a binary message, throws otherwise.
   */
  def asBinaryMessage: BinaryMessage

  def asScala: ws.Message
}

object Message {
  def adapt(msg: ws.Message): Message = msg match {
    case t: ws.TextMessage   ⇒ TextMessage.adapt(t)
    case b: ws.BinaryMessage ⇒ BinaryMessage.adapt(b)
  }
}

/**
 * Represents a Websocket text message. A text message can either be strict in which case
 * the complete data is already available or it can be streamed in which case [[getStreamedText]]
 * will return a Source streaming the data as it comes in.
 */
abstract class TextMessage extends Message {
  /** Is this message a strict one? */
  def isStrict: Boolean

  /**
   * Returns the strict message text if this message is strict, throws otherwise.
   */
  def getStrictText: String

  /**
   * Returns a source of the text message data.
   */
  def getStreamedText: Source[String, _]

  def isText: Boolean = true
  def asTextMessage: TextMessage = this
  def asBinaryMessage: BinaryMessage = throw new ClassCastException("This message is not a binary message.")
  def asScala: ws.TextMessage
}

object TextMessage {
  /**
   * Creates a strict text message.
   */
  def create(text: String): TextMessage =
    new TextMessage {
      def isStrict: Boolean = true
      def getStreamedText: Source[String, _] = Source.single(text)
      def getStrictText: String = text

      def asScala: ws.TextMessage = ws.TextMessage.Strict(text)
    }
  /**
   * Creates a streamed text message.
   */
  def create(textStream: Source[String, _]): TextMessage =
    new TextMessage {
      def isStrict: Boolean = false
      def getStrictText: String = throw new IllegalStateException("Cannot get strict text for streamed message.")
      def getStreamedText: Source[String, _] = textStream

      def asScala: ws.TextMessage = ws.TextMessage.Streamed(textStream.asScala)
    }

  def adapt(msg: ws.TextMessage): TextMessage = msg match {
    case ws.TextMessage.Strict(text)     ⇒ create(text)
    case ws.TextMessage.Streamed(stream) ⇒ create(stream.asJava)
  }
}

abstract class BinaryMessage extends Message {
  /** Is this message a strict one? */
  def isStrict: Boolean

  /**
   * Returns the strict message data if this message is strict, throws otherwise.
   */
  def getStrictData: ByteString

  /**
   * Returns a source of the binary message data.
   */
  def getStreamedData: Source[ByteString, _]

  def isText: Boolean = false
  def asTextMessage: TextMessage = throw new ClassCastException("This message is not a text message.")
  def asBinaryMessage: BinaryMessage = this
  def asScala: ws.BinaryMessage
}

object BinaryMessage {
  /**
   * Creates a strict binary message.
   */
  def create(data: ByteString): BinaryMessage =
    new BinaryMessage {
      def isStrict: Boolean = true
      def getStreamedData: Source[ByteString, _] = Source.single(data)
      def getStrictData: ByteString = data

      def asScala: ws.BinaryMessage = ws.BinaryMessage.Strict(data)
    }

  /**
   * Creates a streamed binary message.
   */
  def create(dataStream: Source[ByteString, _]): BinaryMessage =
    new BinaryMessage {
      def isStrict: Boolean = false
      def getStrictData: ByteString = throw new IllegalStateException("Cannot get strict data for streamed message.")
      def getStreamedData: Source[ByteString, _] = dataStream

      def asScala: ws.BinaryMessage = ws.BinaryMessage.Streamed(dataStream.asScala)
    }

  def adapt(msg: ws.BinaryMessage): BinaryMessage = msg match {
    case ws.BinaryMessage.Strict(data)     ⇒ create(data)
    case ws.BinaryMessage.Streamed(stream) ⇒ create(stream.asJava)
  }
}
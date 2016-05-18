/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model.ws

import akka.stream.scaladsl.Source
import akka.util.ByteString

//#message-model
/**
 * The ADT for WebSocket messages. A message can either be a binary or a text message.
 */
sealed trait Message // FIXME: Why don't we extend akka.http.javadsl.model.ws.Message here?

/**
 * Represents a WebSocket text message. A text message can either be a [[TextMessage.Strict]] in which case
 * the complete data is already available or it can be [[TextMessage.Streamed]] in which case `textStream`
 * will return a Source streaming the data as it comes in.
 */
sealed trait TextMessage extends Message {
  /**
   * The contents of this message as a stream.
   */
  def textStream: Source[String, _]
}
//#message-model
object TextMessage {
  def apply(text: String): Strict = Strict(text)
  def apply(textStream: Source[String, Any]): TextMessage =
    Streamed(textStream)

  /**
   * A strict [[TextMessage]] that contains the complete data as a [[String]].
   */
  final case class Strict(text: String) extends TextMessage {
    def textStream: Source[String, _] = Source.single(text)
    override def toString: String = s"TextMessage.Strict($text)"
  }
  final case class Streamed(textStream: Source[String, _]) extends TextMessage {
    override def toString: String = s"TextMessage.Streamed($textStream)"
  }
}

/**
 * Represents a WebSocket binary message. A binary message can either be [[BinaryMessage.Strict]] in which case
 * the complete data is already available or it can be [[BinaryMessage.Streamed]] in which case `dataStream`
 * will return a Source streaming the data as it comes in.
 */
//#message-model
sealed trait BinaryMessage extends Message {
  /**
   * The contents of this message as a stream.
   */
  def dataStream: Source[ByteString, _]
}
//#message-model
object BinaryMessage {
  def apply(data: ByteString): Strict = Strict(data)
  def apply(dataStream: Source[ByteString, Any]): BinaryMessage =
    Streamed(dataStream)

  /**
   * A strict [[BinaryMessage]] that contains the complete data as a [[akka.util.ByteString]].
   */
  final case class Strict(data: ByteString) extends BinaryMessage {
    def dataStream: Source[ByteString, _] = Source.single(data)
    override def toString: String = s"BinaryMessage.Strict($data)"
  }
  final case class Streamed(dataStream: Source[ByteString, _]) extends BinaryMessage {
    override def toString: String = s"BinaryMessage.Streamed($dataStream)"
  }
}

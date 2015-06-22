/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.model.ws

import akka.stream.scaladsl.Source
import akka.util.ByteString

/**
 * The ADT for Websocket messages. A message can either be a binary or a text message.
 */
sealed trait Message

/**
 * A binary
 */
trait TextMessage extends Message {
  /**
   * The contents of this message as a stream.
   */
  def textStream: Source[String, _]
}
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
  final private case class Streamed(textStream: Source[String, _]) extends TextMessage
}
trait BinaryMessage extends Message {
  /**
   * The contents of this message as a stream.
   */
  def dataStream: Source[ByteString, _]
}
object BinaryMessage {
  def apply(data: ByteString): Strict = Strict(data)
  def apply(dataStream: Source[ByteString, Any]): BinaryMessage =
    Streamed(dataStream)

  /**
   * A strict [[BinaryMessage]] that contains the complete data as a [[ByteString]].
   */
  final case class Strict(data: ByteString) extends BinaryMessage {
    def dataStream: Source[ByteString, _] = Source.single(data)
    override def toString: String = s"BinaryMessage.Strict($data)"
  }
  final private case class Streamed(dataStream: Source[ByteString, _]) extends BinaryMessage
}
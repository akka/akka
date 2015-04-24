/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.model.ws

import akka.stream.scaladsl.Source
import akka.util.ByteString

/**
 * The ADT for Websocket messages. A message can either be binary or a text message. Each of
 * those can either be strict or streamed.
 */
sealed trait Message
sealed trait TextMessage extends Message
object TextMessage {
  final case class Strict(text: String) extends TextMessage {
    override def toString: String = s"TextMessage.Strict($text)"
  }
  final case class Streamed(textStream: Source[String, _]) extends TextMessage
}

sealed trait BinaryMessage extends Message
object BinaryMessage {
  final case class Strict(data: ByteString) extends BinaryMessage {
    override def toString: String = s"BinaryMessage.Strict($data)"
  }
  final case class Streamed(dataStream: Source[ByteString, _]) extends BinaryMessage
}
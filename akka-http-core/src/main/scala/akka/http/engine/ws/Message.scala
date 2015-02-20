/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.ws

import akka.stream.scaladsl.Source
import akka.util.ByteString

sealed trait Message
sealed trait TextMessage extends Message
object TextMessage {
  final case class Strict(text: String) extends TextMessage
  final case class Streamed(text: Source[String]) extends TextMessage
}
sealed trait BinaryMessage extends Message
object BinaryMessage {
  final case class Strict(data: ByteString) extends BinaryMessage
  final case class Streamed(data: Source[ByteString]) extends BinaryMessage
}
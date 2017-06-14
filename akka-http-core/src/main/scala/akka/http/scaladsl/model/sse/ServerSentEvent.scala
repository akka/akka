/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http
package scaladsl
package model
package sse

import akka.http.javadsl.model
import akka.util.ByteString
import java.nio.charset.StandardCharsets.UTF_8
import scala.annotation.tailrec
import scala.compat.java8.OptionConverters.RichOptionForJava8

object ServerSentEvent {

  /**
   * A [[ServerSentEvent]] with empty data which can be used as a heartbeat.
   */
  val heartbeat: ServerSentEvent =
    ServerSentEvent("")

  /**
   * Creates a [[ServerSentEvent]].
   *
   * @param data data, may span multiple lines
   * @param type type, must not contain \n or \r
   */
  def apply(data: String, `type`: String): ServerSentEvent =
    new ServerSentEvent(data, Some(`type`))

  /**
   * Creates a [[ServerSentEvent]].
   *
   * @param data data, may span multiple lines
   * @param type type, must not contain \n or \r
   * @param id id, must not contain \n or \r
   */
  def apply(data: String, `type`: String, id: String): ServerSentEvent =
    new ServerSentEvent(data, Some(`type`), Some(id))

  /**
   * Creates a [[ServerSentEvent]].
   *
   * @param data data, may span multiple lines
   * @param retry reconnection delay in milliseconds
   */
  def apply(data: String, retry: Int): ServerSentEvent =
    new ServerSentEvent(data, retry = Some(retry))

  private def noNewLine(s: String) = s.forall(c ⇒ c != '\n' && c != '\r')

  // Public domain algorithm: http://graphics.stanford.edu/~seander/bithacks.html#RoundUpPowerOf2.
  // We want powers of two both because they typically work better with the allocator, and because we want to minimize
  // reallocations/buffer growth.
  private def nextPowerOfTwoBiggerThan(n: Int) = {
    var m = n - 1
    m |= m >> 1
    m |= m >> 2
    m |= m >> 4
    m |= m >> 8
    m |= m >> 16
    m + 1
  }
}

/**
 * Representation of a server-sent event. According to the specification, an empty data field designates an event
 * which is to be ignored which is useful for heartbeats.
 *
 * @param data data, may span multiple lines
 * @param eventType optional type, must not contain \n or \r
 * @param id optional id, must not contain \n or \r
 * @param retry optional reconnection delay in milliseconds
 */
final case class ServerSentEvent(
  data:      String,
  eventType: Option[String] = None,
  id:        Option[String] = None,
  retry:     Option[Int]    = None)
  extends model.sse.ServerSentEvent {
  import ServerSentEvent._

  require(eventType.forall(noNewLine), "eventType must not contain \\n or \\r!")
  require(id.forall(noNewLine), "id must not contain \\n or \\r!")
  require(retry.forall(_ > 0), "retry must be a positive number!")

  private[scaladsl] def encode = {
    def s = { // Performance fun fact: change this to val and get an ~30% performance penalty!!!
      // Why 8? "data:" == 5 + \n\n (1 data (at least) and 1 ending) == 2 and then we add 1 extra to allocate
      //        a bigger memory slab than data.length since we're going to add data ("data:" + "\n") per line
      // Why 7? "event:" + \n == 7 chars
      // Why 4? "id:" + \n == 4 chars
      // Why 17? "retry:" + \n + Integer.Max decimal places
      val builder =
        new StringBuilder(
          nextPowerOfTwoBiggerThan(
            8 + data.length + eventType.fold(0)(_.length + 7) + id.fold(0)(_.length + 4) + retry.fold(0)(_ ⇒ 17)
          )
        )
      @tailrec def appendData(s: String, index: Int = 0): Unit = {
        @tailrec def addLine(index: Int): Int =
          if (index >= s.length)
            -1
          else {
            val c = s.charAt(index)
            builder.append(c)
            if (c == '\n') index + 1 else addLine(index + 1)
          }
        builder.append("data:")
        addLine(index) match {
          case -1 ⇒ builder.append('\n')
          case i  ⇒ appendData(s, i)
        }
      }
      appendData(data)
      if (eventType.isDefined && eventType.get.nonEmpty) builder.append("event:").append(eventType.get).append('\n')
      if (id.isDefined) builder.append("id:").append(id.get).append('\n')
      if (retry.isDefined) builder.append("retry:").append(retry.get).append('\n')
      builder.append('\n').toString
    }
    ByteString(s, UTF_8.name)
  }

  override def getData = data

  override def getEventType = eventType.asJava

  override def getId = id.asJava

  override def getRetry = retry.asPrimitive
}

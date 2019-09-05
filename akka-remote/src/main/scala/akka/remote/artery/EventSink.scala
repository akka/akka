/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery

import akka.annotation.InternalApi

/**
 * INTERNAL API
 */
@InternalApi
private[akka] trait EventSink {
  def loFreq(eventId: Int, data: Array[Byte]): Unit
  def loFreq(eventId: Int, data: String): Unit
  def hiFreq(eventId: Int, data: Long): Unit
  def alert(eventId: Int, data: Array[Byte]): Unit
}

object IgnoreEventSink extends EventSink {
  def loFreq(eventId: Int, data: Array[Byte]): Unit = ()
  def loFreq(eventId: Int, data: String): Unit = ()
  def hiFreq(eventId: Int, data: Long): Unit = ()
  def alert(eventId: Int, data: Array[Byte]): Unit = ()
}

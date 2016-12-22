/*
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine

import java.net.InetSocketAddress
import java.util.concurrent.TimeoutException

import akka.NotUsed
import akka.stream.scaladsl.{ BidiFlow, Flow }
import akka.util.ByteString

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NoStackTrace

/** INTERNAL API */
private[akka] object HttpConnectionIdleTimeoutBidi {
  def apply(idleTimeout: FiniteDuration, remoteAddress: Option[InetSocketAddress]): BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] = {
    val connectionToString = remoteAddress match {
      case Some(addr) ⇒ s" on connection to [$addr]"
      case _          ⇒ ""
    }
    val ex = new HttpIdleTimeoutException(
      "HTTP idle-timeout encountered" + connectionToString + ", " +
        "no bytes passed in the last " + idleTimeout + ". " +
        "This is configurable by akka.http.[server|client].idle-timeout.", idleTimeout)

    val mapError = Flow[ByteString].mapError({ case t: TimeoutException ⇒ ex })

    val toNetTimeout: BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] =
      BidiFlow.fromFlows(
        mapError,
        Flow[ByteString]
      )
    val fromNetTimeout: BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] =
      toNetTimeout.reversed

    fromNetTimeout atop BidiFlow.bidirectionalIdleTimeout[ByteString, ByteString](idleTimeout) atop toNetTimeout
  }

}

class HttpIdleTimeoutException(msg: String, timeout: FiniteDuration) extends TimeoutException(msg: String) with NoStackTrace

/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import org.zeromq.{ZMQ => JZMQ}
import akka.zeromq.SocketType._

private[zeromq] class Context(numIoThreads: Int = 1) {
  private var context = JZMQ.context(numIoThreads)
  def socket(socketType: SocketType) = {
    context.socket(socketType.id)
  }
  def poller = {
    context.poller
  }
  def term = {
    context.term
  }
}

/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import org.zeromq.{ ZMQ ⇒ JZMQ }
import akka.zeromq.SocketType._

class Context(numIoThreads: Int) {
  private val context = JZMQ.context(numIoThreads)
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

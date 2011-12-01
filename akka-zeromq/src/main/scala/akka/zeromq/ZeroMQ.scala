/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import akka.actor.{Actor, ActorRef}
import akka.dispatch.{Dispatchers, MessageDispatcher}
import akka.zeromq.SocketType._
import akka.util.Duration
import akka.util.duration._

case class SocketParameters(
  context: Context,
  socketType: SocketType,
  listener: Option[ActorRef] = None,
  deserializer: Deserializer = new ZMQMessageDeserializer,
  pollTimeoutDuration: Duration = 100 millis,
  options: Seq[SocketOption] = Seq.empty)

trait SocketOption {
  type OptionType
  def value: OptionType
}

trait IntSocketOption extends SocketOption { type OptionType = Int }
trait LongSocketOption extends SocketOption { type OptionType = Long }
trait StringSocketOption extends SocketOption { type OptionType = String }
trait BoolSocketOption extends SocketOption { type OptionType = Boolean }
trait DeserializerSocketOption extends SocketOption { type OptionType = Deserializer }
trait ActorRefSocketOption extends SocketOption { type OptionType = ActorRef }

abstract class LingerOption(val value: Long) extends LongSocketOption
case class Linger(override val value: Long) extends LingerOption(value)
case object NoLinger extends LingerOption(0)
case class HWM(value: Long) extends LongSocketOption
case class Affinity(value: Long) extends LongSocketOption
case class Rate(value: Long) extends LongSocketOption
case class RecoveryIVL(value: Long) extends LongSocketOption
case class SndBuf(value: Long) extends LongSocketOption
case class RcvBuf(value: Long) extends LongSocketOption
case class Identity(value: String) extends StringSocketOption
case class McastLoop(value: Boolean) extends BoolSocketOption

object Timeout {
  def apply(value: Duration): Timeout = new Timeout(value.toMillis)
}
case class Timeout(value: Long) extends LongSocketOption
case class MessageDeserializer(value: Deserializer) extends DeserializerSocketOption
case class SocketListener(value: ActorRef) extends ActorRefSocketOption

object ZeroMQ {
  def newContext(numIoThreads: Int = 1) = {
    new Context(numIoThreads)
  }
  def newSocket(params: SocketParameters, supervisor: Option[ActorRef] = None, dispatcher: MessageDispatcher = Dispatchers.defaultGlobalDispatcher) = {
    val socket = Actor.actorOf(new ConcurrentSocketActor(params, dispatcher))
    supervisor.foreach(_.link(socket))
    socket.start
  }
}

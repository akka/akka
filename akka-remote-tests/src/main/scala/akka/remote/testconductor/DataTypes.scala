/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.testconductor

import org.jboss.netty.handler.codec.oneone.OneToOneEncoder
import org.jboss.netty.channel.ChannelHandlerContext
import org.jboss.netty.channel.Channel
import akka.remote.testconductor.{ TestConductorProtocol ⇒ TCP }
import com.google.protobuf.Message
import akka.actor.Address
import org.jboss.netty.handler.codec.oneone.OneToOneDecoder

case class Send(msg: NetworkOp)

sealed trait ClientOp // messages sent to Player FSM
sealed trait ServerOp // messages sent to Conductor FSM
sealed trait NetworkOp // messages sent over the wire

case class Hello(name: String, addr: Address) extends NetworkOp
case class EnterBarrier(name: String) extends ClientOp with ServerOp with NetworkOp
case class BarrierFailed(name: String) extends NetworkOp
case class Throttle(node: String, target: String, direction: Direction, rateMBit: Float) extends ServerOp
case class ThrottleMsg(target: Address, direction: Direction, rateMBit: Float) extends NetworkOp
case class Disconnect(node: String, target: String, abort: Boolean) extends ServerOp
case class DisconnectMsg(target: Address, abort: Boolean) extends NetworkOp
case class Terminate(node: String, exitValueOrKill: Int) extends ServerOp
case class TerminateMsg(exitValue: Int) extends NetworkOp
abstract class Done extends NetworkOp
case object Done extends Done {
  def getInstance: Done = this
}

case class Remove(node: String) extends ServerOp

class MsgEncoder extends OneToOneEncoder {
  def encode(ctx: ChannelHandlerContext, ch: Channel, msg: AnyRef): AnyRef = msg match {
    case x: NetworkOp ⇒
      val w = TCP.Wrapper.newBuilder
      x match {
        case Hello(name, addr) ⇒
          w.setHello(TCP.Hello.newBuilder.setName(name).setAddress(addr))
        case EnterBarrier(name) ⇒
          w.setBarrier(TCP.EnterBarrier.newBuilder.setName(name))
        case BarrierFailed(name) ⇒
          w.setBarrier(TCP.EnterBarrier.newBuilder.setName(name).setFailed(true))
        case ThrottleMsg(target, dir, rate) ⇒
          w.setFailure(TCP.InjectFailure.newBuilder.setAddress(target)
            .setFailure(TCP.FailType.Throttle).setDirection(dir).setRateMBit(rate))
        case DisconnectMsg(target, abort) ⇒
          w.setFailure(TCP.InjectFailure.newBuilder.setAddress(target)
            .setFailure(if (abort) TCP.FailType.Abort else TCP.FailType.Disconnect))
        case TerminateMsg(exitValue) ⇒
          w.setFailure(TCP.InjectFailure.newBuilder.setFailure(TCP.FailType.Shutdown).setExitValue(exitValue))
        case _: Done ⇒
          w.setDone("")
      }
      w.build
    case _ ⇒ throw new IllegalArgumentException("wrong message " + msg)
  }
}

class MsgDecoder extends OneToOneDecoder {
  def decode(ctx: ChannelHandlerContext, ch: Channel, msg: AnyRef): AnyRef = msg match {
    case w: TCP.Wrapper if w.getAllFields.size == 1 ⇒
      if (w.hasHello) {
        val h = w.getHello
        Hello(h.getName, h.getAddress)
      } else if (w.hasBarrier) {
        val barrier = w.getBarrier
        if (barrier.hasFailed && barrier.getFailed) BarrierFailed(barrier.getName)
        else EnterBarrier(w.getBarrier.getName)
      } else if (w.hasFailure) {
        val f = w.getFailure
        import TCP.{ FailType ⇒ FT }
        f.getFailure match {
          case FT.Throttle   ⇒ ThrottleMsg(f.getAddress, f.getDirection, f.getRateMBit)
          case FT.Abort      ⇒ DisconnectMsg(f.getAddress, true)
          case FT.Disconnect ⇒ DisconnectMsg(f.getAddress, false)
          case FT.Shutdown   ⇒ TerminateMsg(f.getExitValue)
        }
      } else if (w.hasDone) {
        Done
      } else {
        throw new IllegalArgumentException("unknown message " + msg)
      }
    case _ ⇒ throw new IllegalArgumentException("wrong message " + msg)
  }
}

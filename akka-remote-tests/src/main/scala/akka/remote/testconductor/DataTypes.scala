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

case class ToClient(msg: ClientOp with NetworkOp)
case class ToServer(msg: ServerOp with NetworkOp)

sealed trait ClientOp // messages sent to from Conductor to Player
sealed trait ServerOp // messages sent to from Player to Conductor
sealed trait CommandOp // messages sent from TestConductorExt to Conductor
sealed trait NetworkOp // messages sent over the wire
sealed trait UnconfirmedClientOp extends ClientOp // unconfirmed messages going to the Player
sealed trait ConfirmedClientOp extends ClientOp

/**
 * First message of connection sets names straight.
 */
case class Hello(name: String, addr: Address) extends NetworkOp

case class EnterBarrier(name: String) extends ServerOp with NetworkOp
case class BarrierResult(name: String, success: Boolean) extends UnconfirmedClientOp with NetworkOp

case class Throttle(node: String, target: String, direction: Direction, rateMBit: Float) extends CommandOp
case class ThrottleMsg(target: Address, direction: Direction, rateMBit: Float) extends ConfirmedClientOp with NetworkOp

case class Disconnect(node: String, target: String, abort: Boolean) extends CommandOp
case class DisconnectMsg(target: Address, abort: Boolean) extends ConfirmedClientOp with NetworkOp

case class Terminate(node: String, exitValueOrKill: Int) extends CommandOp
case class TerminateMsg(exitValue: Int) extends ConfirmedClientOp with NetworkOp

case class GetAddress(node: String) extends ServerOp with NetworkOp
case class AddressReply(node: String, addr: Address) extends UnconfirmedClientOp with NetworkOp

abstract class Done extends ServerOp with UnconfirmedClientOp with NetworkOp
case object Done extends Done {
  def getInstance: Done = this
}

case class Remove(node: String) extends CommandOp

class MsgEncoder extends OneToOneEncoder {
  def encode(ctx: ChannelHandlerContext, ch: Channel, msg: AnyRef): AnyRef = msg match {
    case x: NetworkOp ⇒
      val w = TCP.Wrapper.newBuilder
      x match {
        case Hello(name, addr) ⇒
          w.setHello(TCP.Hello.newBuilder.setName(name).setAddress(addr))
        case EnterBarrier(name) ⇒
          w.setBarrier(TCP.EnterBarrier.newBuilder.setName(name))
        case BarrierResult(name, success) ⇒
          w.setBarrier(TCP.EnterBarrier.newBuilder.setName(name).setStatus(success))
        case ThrottleMsg(target, dir, rate) ⇒
          w.setFailure(TCP.InjectFailure.newBuilder.setAddress(target)
            .setFailure(TCP.FailType.Throttle).setDirection(dir).setRateMBit(rate))
        case DisconnectMsg(target, abort) ⇒
          w.setFailure(TCP.InjectFailure.newBuilder.setAddress(target)
            .setFailure(if (abort) TCP.FailType.Abort else TCP.FailType.Disconnect))
        case TerminateMsg(exitValue) ⇒
          w.setFailure(TCP.InjectFailure.newBuilder.setFailure(TCP.FailType.Shutdown).setExitValue(exitValue))
        case GetAddress(node) ⇒
          w.setAddr(TCP.AddressRequest.newBuilder.setNode(node))
        case AddressReply(node, addr) ⇒
          w.setAddr(TCP.AddressRequest.newBuilder.setNode(node).setAddr(addr))
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
        if (barrier.hasStatus) BarrierResult(barrier.getName, barrier.getStatus)
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
      } else if (w.hasAddr) {
        val a = w.getAddr
        if (a.hasAddr) AddressReply(a.getNode, a.getAddr)
        else GetAddress(a.getNode)
      } else if (w.hasDone) {
        Done
      } else {
        throw new IllegalArgumentException("unknown message " + msg)
      }
    case _ ⇒ throw new IllegalArgumentException("wrong message " + msg)
  }
}

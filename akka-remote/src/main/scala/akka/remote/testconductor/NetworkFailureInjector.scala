/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.testconductor

import java.net.InetSocketAddress
import scala.collection.immutable.Queue
import org.jboss.netty.buffer.ChannelBuffer
import org.jboss.netty.channel.ChannelState.BOUND
import org.jboss.netty.channel.ChannelState.OPEN
import org.jboss.netty.channel.Channel
import org.jboss.netty.channel.ChannelEvent
import org.jboss.netty.channel.ChannelHandlerContext
import org.jboss.netty.channel.ChannelStateEvent
import org.jboss.netty.channel.MessageEvent
import akka.actor.FSM
import akka.actor.Actor
import akka.util.duration.doubleToDurationDouble
import akka.util.Index
import akka.actor.Address
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.ActorRef
import akka.event.Logging
import org.jboss.netty.channel.SimpleChannelHandler

case class FailureInjector(sender: ActorRef, receiver: ActorRef) {
  def refs(dir: Direction) = dir match {
    case Direction.Send    ⇒ Seq(sender)
    case Direction.Receive ⇒ Seq(receiver)
    case Direction.Both    ⇒ Seq(sender, receiver)
  }
}

object NetworkFailureInjector {
  case class SetRate(rateMBit: Float)
  case class Disconnect(abort: Boolean)
}

class NetworkFailureInjector(system: ActorSystem) extends SimpleChannelHandler {

  val log = Logging(system, "FailureInjector")

  // everything goes via these Throttle actors to enable easy steering
  private val sender = system.actorOf(Props(new Throttle(_.sendDownstream(_))))
  private val receiver = system.actorOf(Props(new Throttle(_.sendUpstream(_))))

  /*
   * State, Data and Messages for the internal Throttle actor
   */
  sealed private trait State
  private case object PassThrough extends State
  private case object Throttle extends State
  private case object Blackhole extends State

  private case class Data(ctx: ChannelHandlerContext, rateMBit: Float, queue: Queue[MessageEvent])

  private case class Send(ctx: ChannelHandlerContext, msg: MessageEvent)
  private case class SetContext(ctx: ChannelHandlerContext)
  private case object Tick

  private class Throttle(send: (ChannelHandlerContext, MessageEvent) ⇒ Unit) extends Actor with FSM[State, Data] {
    import FSM._

    startWith(PassThrough, Data(null, -1, Queue()))

    when(PassThrough) {
      case Event(Send(ctx, msg), d) ⇒
        log.debug("sending msg (PassThrough): {}", msg)
        send(ctx, msg)
        stay
    }

    when(Throttle) {
      case Event(Send(ctx, msg), d) ⇒
        if (!timerActive_?("send")) {
          setTimer("send", Tick, (size(msg) / d.rateMBit) microseconds, false)
        }
        stay using d.copy(ctx = ctx, queue = d.queue.enqueue(msg))
      case Event(Tick, d) ⇒
        val (msg, queue) = d.queue.dequeue
        log.debug("sending msg (Tick, {}/{} left): {}", d.queue.size, queue.size, msg)
        send(d.ctx, msg)
        if (queue.nonEmpty) {
          val time = (size(queue.head) / d.rateMBit).microseconds
          log.debug("scheduling next Tick in {}", time)
          setTimer("send", Tick, time, false)
        }
        stay using d.copy(queue = queue)
    }

    onTransition {
      case Throttle -> PassThrough ⇒
        stateData.queue foreach { msg ⇒
          log.debug("sending msg (Transition): {}")
          send(stateData.ctx, msg)
        }
        cancelTimer("send")
      case Throttle -> Blackhole ⇒
        cancelTimer("send")
    }

    when(Blackhole) {
      case Event(Send(_, msg), _) ⇒
        log.debug("dropping msg {}", msg)
        stay
    }

    whenUnhandled {
      case Event(SetContext(ctx), d) ⇒ stay using d.copy(ctx = ctx)
      case Event(NetworkFailureInjector.SetRate(rate), d) ⇒
        sender ! "ok"
        if (rate > 0) {
          goto(Throttle) using d.copy(rateMBit = rate, queue = Queue())
        } else if (rate == 0) {
          goto(Blackhole)
        } else {
          goto(PassThrough)
        }
      case Event(NetworkFailureInjector.Disconnect(abort), Data(ctx, _, _)) ⇒
        sender ! "ok"
        // TODO implement abort
        ctx.getChannel.disconnect()
        stay
    }

    initialize

    private def size(msg: MessageEvent) = msg.getMessage() match {
      case b: ChannelBuffer ⇒ b.readableBytes() * 8
      case _                ⇒ throw new UnsupportedOperationException("NetworkFailureInjector only supports ChannelBuffer messages")
    }
  }

  private var remote: Option[Address] = None

  override def messageReceived(ctx: ChannelHandlerContext, msg: MessageEvent) {
    log.debug("upstream(queued): {}", msg)
    receiver ! Send(ctx, msg)
  }

  override def channelConnected(ctx: ChannelHandlerContext, state: ChannelStateEvent) {
    state.getValue match {
      case a: InetSocketAddress ⇒
        val addr = Address("akka", "", a.getHostName, a.getPort)
        log.debug("connected to {}", addr)
        TestConductor(system).failureInjectors.put(addr, FailureInjector(sender, receiver)) match {
          case null ⇒ // okay
          case fi   ⇒ system.log.error("{} already registered for address {}", fi, addr)
        }
        remote = Some(addr)
        sender ! SetContext(ctx)
      case x ⇒ throw new IllegalArgumentException("unknown address type: " + x)
    }
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, state: ChannelStateEvent) {
    log.debug("disconnected from {}", remote)
    remote = remote flatMap { addr ⇒
      TestConductor(system).failureInjectors.remove(addr)
      system.stop(sender)
      system.stop(receiver)
      None
    }
  }

  override def writeRequested(ctx: ChannelHandlerContext, msg: MessageEvent) {
    log.debug("downstream(queued): {}", msg)
    sender ! Send(ctx, msg)
  }

}


/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.testconductor

import java.net.InetSocketAddress
import scala.collection.immutable.Queue
import org.jboss.netty.buffer.ChannelBuffer
import org.jboss.netty.channel.ChannelState.BOUND
import org.jboss.netty.channel.ChannelState.OPEN
import org.jboss.netty.channel.Channel
import org.jboss.netty.channel.ChannelDownstreamHandler
import org.jboss.netty.channel.ChannelEvent
import org.jboss.netty.channel.ChannelHandlerContext
import org.jboss.netty.channel.ChannelStateEvent
import org.jboss.netty.channel.ChannelUpstreamHandler
import org.jboss.netty.channel.MessageEvent
import akka.actor.FSM
import akka.actor.Actor
import akka.util.duration.doubleToDurationDouble
import akka.util.Index
import akka.actor.Address
import akka.actor.ActorSystem
import akka.actor.Props

object NetworkFailureInjector {

  val channels = new Index[Address, Channel](16, (c1, c2) => c1 compareTo c2)

  def close(remote: Address): Unit = {
    // channels will be cleaned up by the handler
    for (chs <- channels.remove(remote); c <- chs) c.close()
  }
}

class NetworkFailureInjector(system: ActorSystem) extends ChannelUpstreamHandler with ChannelDownstreamHandler {

  import NetworkFailureInjector._

  // local cache of remote address
  private var remote: Option[Address] = None

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

  private case class SetRate(rateMBit: Float)
  private case class Send(ctx: ChannelHandlerContext, msg: MessageEvent)
  private case object Tick

  private class Throttle(send: (ChannelHandlerContext, MessageEvent) ⇒ Unit) extends Actor with FSM[State, Data] {
    import FSM._

    startWith(PassThrough, Data(null, -1, Queue()))

    when(PassThrough) {
      case Event(Send(ctx, msg), d) ⇒
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
        send(d.ctx, msg)
        if (queue.nonEmpty) setTimer("send", Tick, (size(queue.head) / d.rateMBit) microseconds, false)
        stay using d.copy(queue = queue)
    }

    onTransition {
      case Throttle -> PassThrough ⇒
        stateData.queue foreach (send(stateData.ctx, _))
        cancelTimer("send")
      case Throttle -> Blackhole ⇒
        cancelTimer("send")
    }

    when(Blackhole) {
      case Event(Send(_, _), _) ⇒
        stay
    }

    whenUnhandled {
      case Event(SetRate(rate), d) ⇒
        if (rate > 0) {
          goto(Throttle) using d.copy(rateMBit = rate, queue = Queue())
        } else if (rate == 0) {
          goto(Blackhole)
        } else {
          goto(PassThrough)
        }
    }

    initialize

    private def size(msg: MessageEvent) = msg.getMessage() match {
      case b: ChannelBuffer ⇒ b.readableBytes() * 8
      case _                ⇒ throw new UnsupportedOperationException("NetworkFailureInjector only supports ChannelBuffer messages")
    }
  }

  def throttleSend(rateMBit: Float) {
    sender ! SetRate(rateMBit)
  }

  def throttleReceive(rateMBit: Float) {
    receiver ! SetRate(rateMBit)
  }

  override def handleUpstream(ctx: ChannelHandlerContext, evt: ChannelEvent) {
    evt match {
      case msg: MessageEvent ⇒
        receiver ! Send(ctx, msg)
      case state: ChannelStateEvent ⇒
        state.getState match {
          case BOUND ⇒
            state.getValue match {
              case null ⇒
                remote = remote flatMap { a ⇒ channels.remove(a, state.getChannel); None }
              case a: InetSocketAddress ⇒
                val addr = Address("akka", "XXX", a.getHostName, a.getPort)
                channels.put(addr, state.getChannel)
                remote = Some(addr)
            }
          case OPEN if state.getValue == false ⇒
            remote = remote flatMap { a ⇒ channels.remove(a, state.getChannel); None }
        }
        ctx.sendUpstream(evt)
      case _ ⇒
        ctx.sendUpstream(evt)
    }
  }

  override def handleDownstream(ctx: ChannelHandlerContext, evt: ChannelEvent) {
    evt match {
      case msg: MessageEvent ⇒
        sender ! Send(ctx, msg)
      case _ ⇒
        ctx.sendUpstream(evt)
    }
  }

}


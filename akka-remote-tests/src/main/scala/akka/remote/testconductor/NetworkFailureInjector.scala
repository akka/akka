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
import scala.annotation.tailrec
import akka.util.Duration
import akka.actor.LoggingFSM
import org.jboss.netty.channel.Channels
import org.jboss.netty.channel.ChannelFuture
import org.jboss.netty.channel.ChannelFutureListener
import org.jboss.netty.channel.ChannelFuture

/**
 * INTERNAL API.
 */
private[akka] case class FailureInjector(sender: ActorRef, receiver: ActorRef) {
  def refs(dir: Direction) = dir match {
    case Direction.Send    ⇒ Seq(sender)
    case Direction.Receive ⇒ Seq(receiver)
    case Direction.Both    ⇒ Seq(sender, receiver)
  }
}

/**
 * INTERNAL API.
 */
private[akka] object NetworkFailureInjector {
  case class SetRate(rateMBit: Float)
  case class Disconnect(abort: Boolean)
}

/**
 * Brief overview: all network traffic passes through the `sender`/`receiver` FSMs, which can
 * pass through requests immediately, drop them or throttle to a desired rate. The FSMs are
 * registered in the TestConductorExt.failureInjectors so that settings can be applied from
 * the ClientFSMs.
 *
 * I found that simply forwarding events using ctx.sendUpstream/sendDownstream does not work,
 * it deadlocks and gives strange errors; in the end I just trusted the Netty docs which
 * recommend to prefer `Channels.write()` and `Channels.fireMessageReceived()`.
 *
 * INTERNAL API.
 */
private[akka] class NetworkFailureInjector(system: ActorSystem) extends SimpleChannelHandler {

  val log = Logging(system, "FailureInjector")

  // everything goes via these Throttle actors to enable easy steering
  private val sender = system.actorOf(Props(new Throttle(Direction.Send)))
  private val receiver = system.actorOf(Props(new Throttle(Direction.Receive)))

  private val packetSplitThreshold = TestConductor(system).Settings.PacketSplitThreshold

  /*
   * State, Data and Messages for the internal Throttle actor
   */
  sealed private trait State
  private case object PassThrough extends State
  private case object Throttle extends State
  private case object Blackhole extends State

  private case class Data(lastSent: Long, rateMBit: Float, queue: Queue[Send])

  private case class Send(ctx: ChannelHandlerContext, future: Option[ChannelFuture], msg: AnyRef)
  private case class SetContext(ctx: ChannelHandlerContext)
  private case object Tick

  private class Throttle(dir: Direction) extends Actor with LoggingFSM[State, Data] {
    import FSM._

    var channelContext: ChannelHandlerContext = _

    startWith(PassThrough, Data(0, -1, Queue()))

    when(PassThrough) {
      case Event(s @ Send(_, _, msg), _) ⇒
        log.debug("sending msg (PassThrough): {}", msg)
        send(s)
        stay
    }

    when(Throttle) {
      case Event(s: Send, data @ Data(_, _, Queue())) ⇒
        stay using sendThrottled(data.copy(lastSent = System.nanoTime, queue = Queue(s)))
      case Event(s: Send, data) ⇒
        stay using sendThrottled(data.copy(queue = data.queue.enqueue(s)))
      case Event(Tick, data) ⇒
        stay using sendThrottled(data)
    }

    onTransition {
      case Throttle -> PassThrough ⇒
        for (s ← stateData.queue) {
          log.debug("sending msg (Transition): {}", s.msg)
          send(s)
        }
        cancelTimer("send")
      case Throttle -> Blackhole ⇒
        cancelTimer("send")
    }

    when(Blackhole) {
      case Event(Send(_, _, msg), _) ⇒
        log.debug("dropping msg {}", msg)
        stay
    }

    whenUnhandled {
      case Event(NetworkFailureInjector.SetRate(rate), d) ⇒
        sender ! "ok"
        if (rate > 0) {
          goto(Throttle) using d.copy(lastSent = System.nanoTime, rateMBit = rate, queue = Queue())
        } else if (rate == 0) {
          goto(Blackhole)
        } else {
          goto(PassThrough)
        }
      case Event(SetContext(ctx), _) ⇒ channelContext = ctx; stay
      case Event(NetworkFailureInjector.Disconnect(abort), Data(ctx, _, _)) ⇒
        sender ! "ok"
        // TODO implement abort
        channelContext.getChannel.disconnect()
        stay
    }

    initialize

    private def sendThrottled(d: Data): Data = {
      val (data, toSend, toTick) = schedule(d)
      for (s ← toSend) {
        log.debug("sending msg (Tick): {}", s.msg)
        send(s)
      }
      if (!timerActive_?("send"))
        for (time ← toTick) {
          log.debug("scheduling next Tick in {}", time)
          setTimer("send", Tick, time, false)
        }
      data
    }

    private def send(s: Send): Unit = dir match {
      case Direction.Send    ⇒ Channels.write(s.ctx, s.future getOrElse Channels.future(s.ctx.getChannel), s.msg)
      case Direction.Receive ⇒ Channels.fireMessageReceived(s.ctx, s.msg)
      case _                 ⇒
    }

    private def schedule(d: Data): (Data, Seq[Send], Option[Duration]) = {
      val now = System.nanoTime
      @tailrec def rec(d: Data, toSend: Seq[Send]): (Data, Seq[Send], Option[Duration]) = {
        if (d.queue.isEmpty) (d, toSend, None)
        else {
          val timeForPacket = d.lastSent + (1000 * size(d.queue.head.msg) / d.rateMBit).toLong
          if (timeForPacket <= now) rec(Data(timeForPacket, d.rateMBit, d.queue.tail), toSend :+ d.queue.head)
          else {
            val splitThreshold = d.lastSent + packetSplitThreshold.toNanos
            if (now < splitThreshold) (d, toSend, Some((timeForPacket - now).nanos min (splitThreshold - now).nanos))
            else {
              val microsToSend = (now - d.lastSent) / 1000
              val (s1, s2) = split(d.queue.head, (microsToSend * d.rateMBit / 8).toInt)
              (d.copy(queue = s2 +: d.queue.tail), toSend :+ s1, Some((timeForPacket - now).nanos min packetSplitThreshold))
            }
          }
        }
      }
      rec(d, Seq())
    }

    private def split(s: Send, bytes: Int): (Send, Send) = {
      s.msg match {
        case buf: ChannelBuffer ⇒
          val f = s.future map { f ⇒
            val newF = Channels.future(s.ctx.getChannel)
            newF.addListener(new ChannelFutureListener {
              def operationComplete(future: ChannelFuture) {
                if (future.isCancelled) f.cancel()
                else future.getCause match {
                  case null ⇒
                  case thr  ⇒ f.setFailure(thr)
                }
              }
            })
            newF
          }
          val b = buf.slice()
          b.writerIndex(b.readerIndex + bytes)
          buf.readerIndex(buf.readerIndex + bytes)
          (Send(s.ctx, f, b), Send(s.ctx, s.future, buf))
      }
    }

    private def size(msg: AnyRef) = msg match {
      case b: ChannelBuffer ⇒ b.readableBytes() * 8
      case _                ⇒ throw new UnsupportedOperationException("NetworkFailureInjector only supports ChannelBuffer messages")
    }
  }

  private var remote: Option[Address] = None

  override def messageReceived(ctx: ChannelHandlerContext, msg: MessageEvent) {
    log.debug("upstream(queued): {}", msg)
    receiver ! Send(ctx, Option(msg.getFuture), msg.getMessage)
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
    sender ! Send(ctx, Option(msg.getFuture), msg.getMessage)
  }

}


/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.testconductor

import language.postfixOps

import java.net.InetSocketAddress

import scala.annotation.tailrec
import scala.collection.immutable.Queue

import org.jboss.netty.buffer.ChannelBuffer
import org.jboss.netty.channel.{ SimpleChannelHandler, MessageEvent, Channels, ChannelStateEvent, ChannelHandlerContext, ChannelFutureListener, ChannelFuture }

import akka.actor.{ Props, LoggingFSM, Address, ActorSystem, ActorRef, ActorLogging, Actor, FSM }
import akka.event.Logging
import akka.remote.netty.ChannelAddress
import scala.concurrent.util.Duration
import scala.concurrent.util.duration._

/**
 * INTERNAL API.
 */
private[akka] class FailureInjector extends Actor with ActorLogging {
  import ThrottleActor._
  import NetworkFailureInjector._

  case class ChannelSettings(
    ctx: Option[ChannelHandlerContext] = None,
    throttleSend: Option[SetRate] = None,
    throttleReceive: Option[SetRate] = None)
  case class Injectors(sender: ActorRef, receiver: ActorRef, known: Boolean)

  var channels = Map[ChannelHandlerContext, Injectors]()
  var settings = Map[Address, ChannelSettings]()
  var generation = Iterator from 1

  /**
   * Only for a NEW ctx, start ThrottleActors, prime them and update all maps.
   */
  def ingestContextAddress(ctx: ChannelHandlerContext, addr: Address, known: Boolean,
                           snd: Option[ActorRef] = None, rcv: Option[ActorRef] = None): Injectors = {
    lazy val gen = generation.next
    val name = addr.host.get + ":" + addr.port.get
    val thrSend = snd getOrElse context.actorOf(Props(new ThrottleActor(ctx)), name + "-snd" + gen)
    val thrRecv = rcv getOrElse context.actorOf(Props(new ThrottleActor(ctx)), name + "-rcv" + gen)
    val injectors = Injectors(thrSend, thrRecv, known)
    channels += ctx -> injectors
    settings += addr -> (settings get addr map {
      case c @ ChannelSettings(prevCtx, ts, tr) ⇒
        ts foreach (thrSend ! _)
        tr foreach (thrRecv ! _)
        prevCtx match {
          case Some(p) ⇒ log.warning("installing context {} instead of {} for address {}", ctx, p, addr)
          case None    ⇒ // okay
        }
        c.copy(ctx = Some(ctx))
    } getOrElse ChannelSettings(Some(ctx)))
    injectors
  }

  /**
   * Retrieve target settings, also if they were sketchy before (i.e. no system name).
   * In the latter case, copy settings from the sketchy address and remove the old
   * mapping.
   */
  def retrieveTargetSettings(target: Address): Option[ChannelSettings] = {
    settings get target orElse {
      val host = target.host
      val port = target.port
      settings find {
        case (Address("akka", "", `host`, `port`), s) ⇒ true
        case _                                        ⇒ false
      } map {
        case (a, s) ⇒ settings -= a; settings += target -> s; s
      }
    }
  }

  def receive = {
    /*
     * If a channel handler tells us that he’s been disconnected, stop the 
     * throttle actors and forget about them (but not possibly applied settings)
     */
    case RemoveContext(ctx) ⇒
      channels get ctx foreach { inj ⇒
        context stop inj.sender
        context stop inj.receiver
      }
      channels -= ctx
      settings ++= settings collect { case (addr, c @ ChannelSettings(Some(`ctx`), _, _)) ⇒ (addr, c.copy(ctx = None)) }
    /*
     * Throttle/Blackhole/Unblock connections, based on the sign of rateMBit; 
     * will inform throttle actors for that destination if currently connected
     * and update the settings for the target address; reply is needed to 
     * confirm this operation and tell the master that he can proceed.
     */
    case ThrottleMsg(target, dir, rateMBit) ⇒
      val setting = retrieveTargetSettings(target)
      settings += target -> ((setting getOrElse ChannelSettings() match {
        case cs @ ChannelSettings(ctx, _, _) if dir includes Direction.Send ⇒
          ctx foreach (c ⇒ channels get c foreach (_.sender ! SetRate(rateMBit)))
          cs.copy(throttleSend = Some(SetRate(rateMBit)))
        case x ⇒ x
      }) match {
        case cs @ ChannelSettings(ctx, _, _) if dir includes Direction.Receive ⇒
          ctx foreach (c ⇒ channels get c foreach (_.receiver ! SetRate(rateMBit)))
          cs.copy(throttleReceive = Some(SetRate(rateMBit)))
        case x ⇒ x
      })
      sender ! "ok"
    /*
     * Disconnect the currently active connection to the given target; reply is
     * needed to confirm this operation and tell the master the he can proceed.
     */
    case DisconnectMsg(target, abort) ⇒
      retrieveTargetSettings(target) foreach {
        case ChannelSettings(Some(ctx), _, _) ⇒
          val ch = ctx.getChannel
          if (abort) {
            ch.getConfig.setOption("soLinger", 0)
            log.info("aborting connection {}", ch)
          } else log.info("closing connection {}", ch)
          ch.close
        case _ ⇒ log.debug("no connection to {} to close or abort", target)
      }
      sender ! "ok"
    /*
     * All data transfers up or down the pipeline are redirected through this
     * case statement, which dispatches to the throttle actors for the given
     * channel handler context. If none exist yet, they will be created, and
     * this is a bit complicated in the case where the first message has not
     * yet been exchanged, i.e. the other side’s Address is not yet known
     * (keep in mind that an actor system’s remote address is not necessarily
     * connected in any way to the IP from which we receive the connection).
     */
    case s @ Send(ctx, direction, future, msg) ⇒
      channels get ctx match {
        case Some(Injectors(snd, rcv, known)) ⇒
          // if the system registered with an empty name then check if we know it now
          if (!known) ChannelAddress.get(ctx.getChannel).foreach(addr ⇒
            ingestContextAddress(ctx, addr, true, Some(snd), Some(rcv)))
          if (direction includes Direction.Send) snd ! s
          if (direction includes Direction.Receive) rcv ! s
        case None ⇒
          // don’t do reverse lookup at first
          ctx.getChannel.getRemoteAddress match {
            case sockAddr: InetSocketAddress ⇒
              val (ipaddr, ip, port) = (sockAddr.getAddress, sockAddr.getAddress.getHostAddress, sockAddr.getPort)
              val (addr, known) = ChannelAddress.get(ctx.getChannel) orElse {
                settings collect { case (a @ Address("akka", _, Some(`ip`), Some(`port`)), _) ⇒ a } headOption
              } orElse {
                // only if raw IP failed, try with hostname
                val name = ipaddr.getHostName
                if (name == ip) None
                else settings collect { case (a @ Address("akka", _, Some(`name`), Some(`port`)), _) ⇒ a } headOption
              } match {
                case Some(a) ⇒ (a, true)
                case None    ⇒ (Address("akka", "", ip, port), false)
              }
              /*
               * ^- the above last resort will not match later requests directly, but be 
               * picked up by retrieveTargetSettings, so that throttle ops are
               * applied to the right throttle actors, assuming that there can
               * be only one actor system per host:port.
               */
              val inj = ingestContextAddress(ctx, addr, known)
              if (direction includes Direction.Send) inj.sender ! s
              if (direction includes Direction.Receive) inj.receiver ! s
            case null ⇒
              log.debug("sending {} in direction {} when socket {} already closed, dropping", msg, direction, ctx.getChannel)
          }
      }
  }
}

private[akka] object NetworkFailureInjector {
  case class RemoveContext(ctx: ChannelHandlerContext)
}

/**
 * Brief overview: all network traffic passes through the `sender`/`receiver` FSMs managed
 * by the FailureInjector of the TestConductor extension. These can
 * pass through requests immediately, drop them or throttle to a desired rate. The FSMs are
 * registered in the TestConductorExt.failureInjector so that settings can be applied from
 * the ClientFSMs.
 *
 * I found that simply forwarding events using ctx.sendUpstream/sendDownstream does not work,
 * it deadlocks and gives strange errors; in the end I just trusted the Netty docs which
 * recommend to prefer `Channels.write()` and `Channels.fireMessageReceived()`.
 *
 * INTERNAL API.
 */
private[akka] class NetworkFailureInjector(system: ActorSystem) extends SimpleChannelHandler {
  import NetworkFailureInjector._

  private val log = Logging(system, "FailureInjector")

  private val conductor = TestConductor(system)
  private var announced = false

  override def channelConnected(ctx: ChannelHandlerContext, state: ChannelStateEvent) {
    state.getValue match {
      case a: InetSocketAddress ⇒
        val addr = Address("akka", "", a.getHostName, a.getPort)
        log.debug("connected to {}", addr)
      case x ⇒ throw new IllegalArgumentException("unknown address type: " + x)
    }
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, state: ChannelStateEvent) {
    log.debug("disconnected from {}", state.getChannel)
    conductor.failureInjector ! RemoveContext(ctx)
  }

  override def messageReceived(ctx: ChannelHandlerContext, msg: MessageEvent) {
    log.debug("upstream(queued): {}", msg)
    conductor.failureInjector ! ThrottleActor.Send(ctx, Direction.Receive, Option(msg.getFuture), msg.getMessage)
  }

  override def writeRequested(ctx: ChannelHandlerContext, msg: MessageEvent) {
    log.debug("downstream(queued): {}", msg)
    conductor.failureInjector ! ThrottleActor.Send(ctx, Direction.Send, Option(msg.getFuture), msg.getMessage)
  }

}

/**
 * INTERNAL API.
 */
private[akka] object ThrottleActor {
  sealed trait State
  case object PassThrough extends State
  case object Throttle extends State
  case object Blackhole extends State

  case class Data(lastSent: Long, rateMBit: Float, queue: Queue[Send])

  case class Send(ctx: ChannelHandlerContext, direction: Direction, future: Option[ChannelFuture], msg: AnyRef)
  case class SetRate(rateMBit: Float)
  case object Tick
}

/**
 * INTERNAL API.
 */
private[akka] class ThrottleActor(channelContext: ChannelHandlerContext)
  extends Actor with LoggingFSM[ThrottleActor.State, ThrottleActor.Data] {

  import ThrottleActor._
  import FSM._

  private val packetSplitThreshold = TestConductor(context.system).Settings.PacketSplitThreshold

  startWith(PassThrough, Data(0, -1, Queue()))

  when(PassThrough) {
    case Event(s @ Send(_, _, _, msg), _) ⇒
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
    case Event(Send(_, _, _, msg), _) ⇒
      log.debug("dropping msg {}", msg)
      stay
  }

  whenUnhandled {
    case Event(SetRate(rate), d) ⇒
      if (rate > 0) {
        goto(Throttle) using d.copy(lastSent = System.nanoTime, rateMBit = rate, queue = Queue())
      } else if (rate == 0) {
        goto(Blackhole)
      } else {
        goto(PassThrough)
      }
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

  private def send(s: Send): Unit = s.direction match {
    case Direction.Send    ⇒ Channels.write(s.ctx, s.future getOrElse Channels.future(s.ctx.getChannel), s.msg)
    case Direction.Receive ⇒ Channels.fireMessageReceived(s.ctx, s.msg)
    case _                 ⇒
  }

  /**
   * Core of the throttling engine: delay Send operations until their bit count
   * would actually have had time to travel down the line at the configured
   * data rate, and split up send operations which are so big that gaps larger
   * than packetSplitThreshold would be planned (they will happen nevertheless
   * due to HashedWheelTimer’s semantics, but we compensate by sending more the
   * next time, in proportion to how long the Tick was overdue). So, this should
   * lead to the correct rate on average, with increased latency of the order of
   * HWT granularity.
   */
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

  /**
   * Split one Send operation in two, cutting off the given number of bytes at
   * the front. If it was Direction.Send, i.e. a channel.write(), then also
   * split the Future so that a failure in either part will complete the original
   * with that failure. Data are not copied, as long as ChannelBuffer.slice does
   * not copy them.
   */
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
        (Send(s.ctx, s.direction, f, b), Send(s.ctx, s.direction, s.future, buf))
    }
  }

  private def size(msg: AnyRef) = msg match {
    case b: ChannelBuffer ⇒ b.readableBytes() * 8
    case _                ⇒ throw new UnsupportedOperationException("NetworkFailureInjector only supports ChannelBuffer messages")
  }
}


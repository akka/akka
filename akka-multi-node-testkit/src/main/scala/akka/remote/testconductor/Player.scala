/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.testconductor

import java.util.concurrent.TimeoutException
import akka.actor._
import akka.remote.testconductor.RemoteConnection.getAddrString
import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Await, Future }
import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import scala.reflect.classTag
import akka.util.Timeout
import org.jboss.netty.channel.{ Channel, SimpleChannelUpstreamHandler, ChannelHandlerContext, ChannelStateEvent, MessageEvent, WriteCompletionEvent, ExceptionEvent }
import akka.pattern.{ ask, AskTimeoutException }
import akka.event.{ LoggingAdapter, Logging }
import java.net.{ InetSocketAddress, ConnectException }
import akka.remote.transport.ThrottlerTransportAdapter.{ SetThrottle, TokenBucket, Blackhole, Unthrottled }
import akka.dispatch.{ UnboundedMessageQueueSemantics, RequiresMessageQueue }
import akka.util.ccompat._

object Player {

  final class Waiter extends Actor with RequiresMessageQueue[UnboundedMessageQueueSemantics] {

    import FSM._
    import ClientFSM._

    var waiting: ActorRef = _

    def receive = {
      case fsm: ActorRef ⇒
        waiting = sender(); fsm ! SubscribeTransitionCallBack(self)
      case Transition(_, f: ClientFSM.State, t: ClientFSM.State) if f == Connecting && t == AwaitDone ⇒ // step 1, not there yet // // SI-5900 workaround
      case Transition(_, f: ClientFSM.State, t: ClientFSM.State) if f == AwaitDone && t == Connected ⇒ // SI-5900 workaround
        waiting ! Done; context stop self
      case t: Transition[_] ⇒
        waiting ! Status.Failure(new RuntimeException("unexpected transition: " + t)); context stop self
      case CurrentState(_, s: ClientFSM.State) if s == Connected ⇒ // SI-5900 workaround
        waiting ! Done; context stop self
      case _: CurrentState[_] ⇒
    }

  }

  def waiterProps = Props[Waiter]
}

/**
 * The Player is the client component of the
 * [[akka.remote.testconductor.TestConductorExt]] extension. It registers with
 * the [[akka.remote.testconductor.Conductor]]’s [[akka.remote.testconductor.Controller]]
 * in order to participate in barriers and enable network failure injection.
 */
trait Player { this: TestConductorExt ⇒

  private var _client: ActorRef = _
  private def client = _client match {
    case null ⇒
      throw new IllegalStateException("TestConductor client not yet started")
    case _ if system.whenTerminated.isCompleted ⇒
      throw new IllegalStateException("TestConductor unavailable because system is terminated; you need to startNewSystem() before this point")
    case x ⇒ x
  }

  /**
   * Connect to the conductor on the given port (the host is taken from setting
   * `akka.testconductor.host`). The connection is made asynchronously, but you
   * should await completion of the returned Future because that implies that
   * all expected participants of this test have successfully connected (i.e.
   * this is a first barrier in itself). The number of expected participants is
   * set in [[akka.remote.testconductor.Conductor]]`.startController()`.
   */
  def startClient(name: RoleName, controllerAddr: InetSocketAddress): Future[Done] = {
    import Settings.BarrierTimeout

    if (_client ne null) throw new IllegalStateException("TestConductorClient already started")
    _client = system.actorOf(Props(classOf[ClientFSM], name, controllerAddr), "TestConductorClient")
    val a = system.actorOf(Player.waiterProps)
    a ? client mapTo classTag[Done]
  }

  /**
   * Enter the named barriers, one after the other, in the order given. Will
   * throw an exception in case of timeouts or other errors.
   */
  def enter(name: String*): Unit = enter(Settings.BarrierTimeout, name.to(immutable.Seq))

  /**
   * Enter the named barriers, one after the other, in the order given. Will
   * throw an exception in case of timeouts or other errors.
   */
  def enter(timeout: Timeout, name: immutable.Seq[String]): Unit = {
    system.log.debug("entering barriers " + name.mkString("(", ", ", ")"))
    val stop = Deadline.now + timeout.duration
    name foreach { b ⇒
      val barrierTimeout = stop.timeLeft
      if (barrierTimeout < Duration.Zero) {
        client ! ToServer(FailBarrier(b))
        throw new TimeoutException("Server timed out while waiting for barrier " + b)
      }
      try {
        implicit val timeout = Timeout(barrierTimeout + Settings.QueryTimeout.duration)
        Await.result(client ? ToServer(EnterBarrier(b, Option(barrierTimeout))), Duration.Inf)
      } catch {
        case e: AskTimeoutException ⇒
          client ! ToServer(FailBarrier(b))
          // Why don't TimeoutException have a constructor that takes a cause?
          throw new TimeoutException("Client timed out while waiting for barrier " + b);
      }
      system.log.debug("passed barrier {}", b)
    }
  }

  /**
   * Query remote transport address of named node.
   */
  def getAddressFor(name: RoleName): Future[Address] = {
    import Settings.QueryTimeout
    client ? ToServer(GetAddress(name)) mapTo classTag[Address]
  }
}

/**
 * INTERNAL API.
 */
private[akka] object ClientFSM {
  sealed trait State
  case object Connecting extends State
  case object AwaitDone extends State
  case object Connected extends State
  case object Failed extends State

  final case class Data(channel: Option[Channel], runningOp: Option[(String, ActorRef)])

  final case class Connected(channel: Channel) extends NoSerializationVerificationNeeded
  final case class ConnectionFailure(msg: String) extends RuntimeException(msg) with NoStackTrace
  case object Disconnected
}

/**
 * This is the controlling entity on the [[akka.remote.testconductor.Player]]
 * side: in a first step it registers itself with a symbolic name and its remote
 * address at the [[akka.remote.testconductor.Controller]], then waits for the
 * `Done` message which signals that all other expected test participants have
 * done the same. After that, it will pass barrier requests to and from the
 * coordinator and react to the [[akka.remote.testconductor.Conductor]]’s
 * requests for failure injection.
 *
 * Note that you can't perform requests concurrently, e.g. enter barrier
 * from one thread and ask for node address from another thread.
 *
 * INTERNAL API.
 */
private[akka] class ClientFSM(name: RoleName, controllerAddr: InetSocketAddress) extends Actor
  with LoggingFSM[ClientFSM.State, ClientFSM.Data] with RequiresMessageQueue[UnboundedMessageQueueSemantics] {
  import ClientFSM._

  val settings = TestConductor().Settings

  val handler = new PlayerHandler(controllerAddr, settings.ClientReconnects, settings.ReconnectBackoff,
    settings.ClientSocketWorkerPoolSize, self, Logging(context.system, classOf[PlayerHandler].getName),
    context.system.scheduler)(context.dispatcher)

  startWith(Connecting, Data(None, None))

  when(Connecting, stateTimeout = settings.ConnectTimeout) {
    case Event(msg: ClientOp, _) ⇒
      stay replying Status.Failure(new IllegalStateException("not connected yet"))
    case Event(Connected(channel), _) ⇒
      channel.write(Hello(name.name, TestConductor().address))
      goto(AwaitDone) using Data(Some(channel), None)
    case Event(e: ConnectionFailure, _) ⇒
      log.error(e, "ConnectionFailure")
      goto(Failed)
    case Event(StateTimeout, _) ⇒
      log.error("Failed to connect to test conductor within {} ms.", settings.ConnectTimeout.toMillis)
      goto(Failed)
  }

  when(AwaitDone, stateTimeout = settings.BarrierTimeout.duration) {
    case Event(Done, _) ⇒
      log.debug("received Done: starting test")
      goto(Connected)
    case Event(msg: NetworkOp, _) ⇒
      log.error("received {} instead of Done", msg)
      goto(Failed)
    case Event(msg: ServerOp, _) ⇒
      stay replying Status.Failure(new IllegalStateException("not connected yet"))
    case Event(StateTimeout, _) ⇒
      log.error("connect timeout to TestConductor")
      goto(Failed)
  }

  when(Connected) {
    case Event(Disconnected, _) ⇒
      log.info("disconnected from TestConductor")
      throw new ConnectionFailure("disconnect")
    case Event(ToServer(_: Done), Data(Some(channel), _)) ⇒
      channel.write(Done)
      stay
    case Event(ToServer(msg), d @ Data(Some(channel), None)) ⇒
      channel.write(msg)
      val token = msg match {
        case EnterBarrier(barrier, timeout) ⇒ Some(barrier → sender())
        case GetAddress(node)               ⇒ Some(node.name → sender())
        case _                              ⇒ None
      }
      stay using d.copy(runningOp = token)
    case Event(ToServer(op), Data(channel, Some((token, _)))) ⇒
      log.error("cannot write {} while waiting for {}", op, token)
      stay
    case Event(op: ClientOp, d @ Data(Some(channel), runningOp)) ⇒
      op match {
        case BarrierResult(b, success) ⇒
          runningOp match {
            case Some((barrier, requester)) ⇒
              val response =
                if (b != barrier) Status.Failure(new RuntimeException("wrong barrier " + b + " received while waiting for " + barrier))
                else if (!success) Status.Failure(new RuntimeException("barrier failed: " + b))
                else b
              requester ! response
            case None ⇒
              log.warning("did not expect {}", op)
          }
          stay using d.copy(runningOp = None)
        case AddressReply(node, address) ⇒
          runningOp match {
            case Some((_, requester)) ⇒ requester ! address
            case None                 ⇒ log.warning("did not expect {}", op)
          }
          stay using d.copy(runningOp = None)
        case t: ThrottleMsg ⇒
          import context.dispatcher // FIXME is this the right EC for the future below?
          val mode = if (t.rateMBit < 0.0f) Unthrottled
          else if (t.rateMBit == 0.0f) Blackhole
          // Conversion needed as the TokenBucket measures in octets: 125000 Octets/s = 1Mbit/s
          // FIXME: Initial capacity should be carefully chosen
          else TokenBucket(capacity = 1000, tokensPerSecond = t.rateMBit * 125000.0, nanoTimeOfLastSend = 0, availableTokens = 0)

          val cmdFuture = TestConductor().transport.managementCommand(SetThrottle(t.target, t.direction, mode))

          cmdFuture foreach {
            case true ⇒ self ! ToServer(Done)
            case _ ⇒ throw new RuntimeException("Throttle was requested from the TestConductor, but no transport " +
              "adapters available that support throttling. Specify `testTransport(on = true)` in your MultiNodeConfig")
          }
          stay
        case d: DisconnectMsg ⇒
          // FIXME: Currently ignoring, needs support from Remoting
          stay
        case TerminateMsg(Left(false)) ⇒
          context.system.terminate()
          stay
        case TerminateMsg(Left(true)) ⇒
          context.system.asInstanceOf[ActorSystemImpl].abort()
          stay
        case TerminateMsg(Right(exitValue)) ⇒
          System.exit(exitValue)
          stay // needed because Java doesn’t have Nothing
        case _: Done ⇒ stay //FIXME what should happen?
      }
  }

  when(Failed) {
    case Event(msg: ClientOp, _) ⇒
      stay replying Status.Failure(new RuntimeException("cannot do " + msg + " while Failed"))
    case Event(msg: NetworkOp, _) ⇒
      log.warning("ignoring network message {} while Failed", msg)
      stay
  }

  onTermination {
    case StopEvent(_, _, Data(Some(channel), _)) ⇒
      channel.close()
  }

  initialize()
}

/**
 * This handler only forwards messages received from the conductor to the [[akka.remote.testconductor.ClientFSM]].
 *
 * INTERNAL API.
 */
private[akka] class PlayerHandler(
  server:                 InetSocketAddress,
  private var reconnects: Int,
  backoff:                FiniteDuration,
  poolSize:               Int,
  fsm:                    ActorRef,
  log:                    LoggingAdapter,
  scheduler:              Scheduler)(implicit executor: ExecutionContext)
  extends SimpleChannelUpstreamHandler {

  import ClientFSM._

  reconnect()

  var nextAttempt: Deadline = _

  override def channelOpen(ctx: ChannelHandlerContext, event: ChannelStateEvent) = log.debug("channel {} open", event.getChannel)
  override def channelClosed(ctx: ChannelHandlerContext, event: ChannelStateEvent) = log.debug("channel {} closed", event.getChannel)
  override def channelBound(ctx: ChannelHandlerContext, event: ChannelStateEvent) = log.debug("channel {} bound", event.getChannel)
  override def channelUnbound(ctx: ChannelHandlerContext, event: ChannelStateEvent) = log.debug("channel {} unbound", event.getChannel)
  override def writeComplete(ctx: ChannelHandlerContext, event: WriteCompletionEvent) = log.debug("channel {} written {}", event.getChannel, event.getWrittenAmount)

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    log.debug("channel {} exception {}", event.getChannel, event.getCause)
    event.getCause match {
      case c: ConnectException if reconnects > 0 ⇒
        reconnects -= 1
        scheduler.scheduleOnce(nextAttempt.timeLeft)(reconnect())
      case e ⇒ fsm ! ConnectionFailure(e.getMessage)
    }
  }

  private def reconnect(): Unit = {
    nextAttempt = Deadline.now + backoff
    RemoteConnection(Client, server, poolSize, this)
  }

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val ch = event.getChannel
    log.debug("connected to {}", getAddrString(ch))
    fsm ! Connected(ch)
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val channel = event.getChannel
    log.debug("disconnected from {}", getAddrString(channel))
    fsm ! PoisonPill
    executor.execute(new Runnable { def run = RemoteConnection.shutdown(channel) }) // Must be shutdown outside of the Netty IO pool
  }

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) = {
    val channel = event.getChannel
    log.debug("message from {}: {}", getAddrString(channel), event.getMessage)
    event.getMessage match {
      case msg: NetworkOp ⇒
        fsm ! msg
      case msg ⇒
        log.info("server {} sent garbage '{}', disconnecting", getAddrString(channel), msg)
        channel.close()
    }
  }
}


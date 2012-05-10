/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.testconductor

import akka.actor.{ Actor, ActorRef, ActorSystem, LoggingFSM, Props }
import RemoteConnection.getAddrString
import akka.util.duration._
import org.jboss.netty.channel.{ Channel, SimpleChannelUpstreamHandler, ChannelHandlerContext, ChannelStateEvent, MessageEvent }
import com.eaio.uuid.UUID
import com.typesafe.config.ConfigFactory
import akka.util.Timeout
import akka.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.pattern.{ ask, pipe }
import akka.dispatch.Await
import scala.util.control.NoStackTrace
import akka.actor.Status
import akka.event.LoggingAdapter
import akka.actor.PoisonPill
import akka.event.Logging
import akka.dispatch.Future

/**
 * The Player is the client component of the
 * [[akka.remote.testconductor.TestConductorExt]] extension. It registers with
 * the [[akka.remote.testconductor.Conductor]]’s [[akka.remote.testconductor.Controller]]
 * in order to participate in barriers and enable network failure injection.
 */
trait Player { this: TestConductorExt ⇒

  private var _client: ActorRef = _
  private def client = _client match {
    case null ⇒ throw new IllegalStateException("TestConductor client not yet started")
    case x    ⇒ x
  }

  /**
   * Connect to the conductor on the given port (the host is taken from setting
   * `akka.testconductor.host`). The connection is made asynchronously, but you
   * should await completion of the returned Future because that implies that
   * all expected participants of this test have successfully connected (i.e.
   * this is a first barrier in itself). The number of expected participants is
   * set in [[akka.remote.testconductor.Conductor]]`.startController()`.
   */
  def startClient(port: Int): Future[Done] = {
    import ClientFSM._
    import akka.actor.FSM._
    import Settings.BarrierTimeout

    if (_client ne null) throw new IllegalStateException("TestConductorClient already started")
    _client = system.actorOf(Props(new ClientFSM(port)), "TestConductorClient")
    val a = system.actorOf(Props(new Actor {
      var waiting: ActorRef = _
      def receive = {
        case fsm: ActorRef                        ⇒ waiting = sender; fsm ! SubscribeTransitionCallBack(self)
        case Transition(_, Connecting, AwaitDone) ⇒ // step 1, not there yet
        case Transition(_, AwaitDone, Connected)  ⇒ waiting ! Done
        case t: Transition[_]                     ⇒ waiting ! Status.Failure(new RuntimeException("unexpected transition: " + t))
        case CurrentState(_, Connected)           ⇒ waiting ! Done
        case _: CurrentState[_]                   ⇒
      }
    }))

    a ? client mapTo
  }

  /**
   * Enter the named barriers, one after the other, in the order given. Will
   * throw an exception in case of timeouts or other errors.
   */
  def enter(name: String*) {
    system.log.debug("entering barriers " + name.mkString("(", ", ", ")"))
    name foreach { b ⇒
      import Settings.BarrierTimeout
      Await.result(client ? Send(EnterBarrier(b)), Duration.Inf)
      system.log.debug("passed barrier {}", b)
    }
  }
}

object ClientFSM {
  sealed trait State
  case object Connecting extends State
  case object AwaitDone extends State
  case object Connected extends State

  case class Data(channel: Channel, barrier: Option[(String, ActorRef)])

  class ConnectionFailure(msg: String) extends RuntimeException(msg) with NoStackTrace
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
 */
class ClientFSM(port: Int) extends Actor with LoggingFSM[ClientFSM.State, ClientFSM.Data] {
  import ClientFSM._

  val settings = TestConductor().Settings

  val handler = new PlayerHandler(self, Logging(context.system, "PlayerHandler"))

  startWith(Connecting, Data(RemoteConnection(Client, settings.host, port, handler), None))

  when(Connecting, stateTimeout = 10 seconds) {
    case Event(msg: ClientOp, _) ⇒
      stay replying Status.Failure(new IllegalStateException("not connected yet"))
    case Event(Connected, d @ Data(channel, _)) ⇒
      channel.write(Hello(settings.name, TestConductor().address))
      goto(AwaitDone)
    case Event(_: ConnectionFailure, _) ⇒
      // System.exit(1)
      stop
    case Event(StateTimeout, _) ⇒
      log.error("connect timeout to TestConductor")
      // System.exit(1)
      stop
  }

  when(AwaitDone, stateTimeout = settings.BarrierTimeout.duration) {
    case Event(Done, _) ⇒
      log.debug("received Done: starting test")
      goto(Connected)
    case Event(msg: ClientOp, _) ⇒
      stay replying Status.Failure(new IllegalStateException("not connected yet"))
    case Event(StateTimeout, _) ⇒
      log.error("connect timeout to TestConductor")
      // System.exit(1)
      stop
  }

  when(Connected) {
    case Event(Disconnected, _) ⇒
      log.info("disconnected from TestConductor")
      throw new ConnectionFailure("disconnect")
    case Event(Send(msg: EnterBarrier), Data(channel, None)) ⇒
      channel.write(msg)
      stay using Data(channel, Some(msg.name, sender))
    case Event(Send(d: Done), Data(channel, _)) ⇒
      channel.write(d)
      stay
    case Event(Send(x), _) ⇒
      log.warning("cannot send message {}", x)
      stay
    case Event(EnterBarrier(b), Data(channel, Some((barrier, sender)))) ⇒
      if (b != barrier) {
        sender ! Status.Failure(new RuntimeException("wrong barrier " + b + " received while waiting for " + barrier))
      } else {
        sender ! b
      }
      stay using Data(channel, None)
    case Event(BarrierFailed(b), Data(channel, Some((_, sender)))) ⇒
      sender ! Status.Failure(new RuntimeException("barrier failed: " + b))
      stay using Data(channel, None)
    case Event(ThrottleMsg(target, dir, rate), _) ⇒
      import settings.QueryTimeout
      import context.dispatcher
      TestConductor().failureInjectors.get(target.copy(system = "")) match {
        case null ⇒ log.warning("cannot throttle unknown address {}", target)
        case inj ⇒
          Future.sequence(inj.refs(dir) map (_ ? NetworkFailureInjector.SetRate(rate))) map (_ ⇒ Send(Done)) pipeTo self
      }
      stay
    case Event(DisconnectMsg(target, abort), _) ⇒
      import settings.QueryTimeout
      TestConductor().failureInjectors.get(target.copy(system = "")) match {
        case null ⇒ log.warning("cannot disconnect unknown address {}", target)
        case inj  ⇒ inj.sender ? NetworkFailureInjector.Disconnect(abort) map (_ ⇒ Send(Done)) pipeTo self
      }
      stay
    case Event(TerminateMsg(exit), _) ⇒
      System.exit(exit)
      stay // needed because Java doesn’t have Nothing
  }

  onTermination {
    case StopEvent(_, _, Data(channel, _)) ⇒
      channel.close()
  }

  initialize

}

/**
 * This handler only forwards messages received from the conductor to the [[akka.remote.testconductor.ClientFSM]].
 */
class PlayerHandler(fsm: ActorRef, log: LoggingAdapter) extends SimpleChannelUpstreamHandler {

  import ClientFSM._

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val channel = event.getChannel
    log.debug("connected to {}", getAddrString(channel))
    fsm ! Connected
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val channel = event.getChannel
    log.debug("disconnected from {}", getAddrString(channel))
    fsm ! PoisonPill
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


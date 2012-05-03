/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.testconductor

import akka.actor.{ Actor, ActorRef, ActorSystem, LoggingFSM, Props }
import RemoteConnection.getAddrString
import akka.util.duration._
import TestConductorProtocol._
import org.jboss.netty.channel.{ Channel, SimpleChannelUpstreamHandler, ChannelHandlerContext, ChannelStateEvent, MessageEvent }
import com.eaio.uuid.UUID
import com.typesafe.config.ConfigFactory
import akka.util.Timeout
import akka.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.pattern.ask
import akka.dispatch.Await
import scala.util.control.NoStackTrace
import akka.actor.Status
import akka.event.LoggingAdapter
import akka.actor.PoisonPill
import akka.event.Logging

trait Player extends BarrierSync { this: TestConductorExt ⇒

  private var _client: ActorRef = _
  private def client = _client match {
    case null ⇒ throw new IllegalStateException("TestConductor client not yet started")
    case x    ⇒ x
  }

  def startClient(port: Int) {
    import ClientFSM._
    import akka.actor.FSM._
    import Settings.BarrierTimeout

    if (_client ne null) throw new IllegalStateException("TestConductorClient already started")
    _client = system.actorOf(Props(new ClientFSM(port)), "TestConductorClient")
    val a = system.actorOf(Props(new Actor {
      var waiting: ActorRef = _
      def receive = {
        case fsm: ActorRef                        ⇒ waiting = sender; fsm ! SubscribeTransitionCallBack(self)
        case Transition(_, Connecting, Connected) ⇒ waiting ! "okay"
        case t: Transition[_]                     ⇒ waiting ! Status.Failure(new RuntimeException("unexpected transition: " + t))
        case CurrentState(_, Connected)           ⇒ waiting ! "okay"
        case _: CurrentState[_]                   ⇒
      }
    }))

    Await.result(a ? client, Duration.Inf)
  }

  override def enter(name: String*) {
    system.log.debug("entering barriers " + name.mkString("(", ", ", ")"))
    name foreach { b ⇒
      import Settings.BarrierTimeout
      Await.result(client ? EnterBarrier(b), Duration.Inf)
      system.log.debug("passed barrier {}", b)
    }
  }
}

object ClientFSM {
  sealed trait State
  case object Connecting extends State
  case object Connected extends State

  case class Data(channel: Channel, barrier: Option[(String, ActorRef)])

  class ConnectionFailure(msg: String) extends RuntimeException(msg) with NoStackTrace
  case object Disconnected
}

class ClientFSM(port: Int) extends Actor with LoggingFSM[ClientFSM.State, ClientFSM.Data] {
  import ClientFSM._

  val settings = TestConductor().Settings

  val handler = new PlayerHandler(self, Logging(context.system, "PlayerHandler"))

  startWith(Connecting, Data(RemoteConnection(Client, settings.host, port, handler), None))

  when(Connecting, stateTimeout = 10 seconds) {
    case Event(msg: ClientOp, _) ⇒
      stay replying Status.Failure(new IllegalStateException("not connected yet"))
    case Event(Connected, d @ Data(channel, _)) ⇒
      val hello = Hello.newBuilder.setName(settings.name).setAddress(TestConductor().address).build
      channel.write(Wrapper.newBuilder.setHello(hello).build)
      goto(Connected)
    case Event(_: ConnectionFailure, _) ⇒
      // System.exit(1)
      stop
    case Event(StateTimeout, _) ⇒
      log.error("connect timeout to TestConductor")
      // System.exit(1)
      stop
  }

  when(Connected) {
    case Event(Disconnected, _) ⇒
      log.info("disconnected from TestConductor")
      throw new ConnectionFailure("disconnect")
    case Event(msg: EnterBarrier, Data(channel, _)) ⇒
      sendMsg(channel)(msg)
      stay using Data(channel, Some(msg.name, sender))
    case Event(msg: Wrapper, Data(channel, Some((barrier, sender)))) if msg.getAllFields.size == 1 ⇒
      if (msg.hasBarrier) {
        val b = msg.getBarrier.getName
        if (b != barrier) {
          sender ! Status.Failure(new RuntimeException("wrong barrier " + b + " received while waiting for " + barrier))
        } else {
          sender ! b
        }
      }
      stay using Data(channel, None)
  }

  onTermination {
    case StopEvent(_, _, Data(channel, _)) ⇒
      channel.close()
  }

  initialize

  private def sendMsg(channel: Channel)(msg: ClientOp) {
    msg match {
      case EnterBarrier(name) ⇒
        val enter = TestConductorProtocol.EnterBarrier.newBuilder.setName(name).build
        channel.write(Wrapper.newBuilder.setBarrier(enter).build)
    }
  }

}

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
      case msg: Wrapper if msg.getAllFields.size == 1 ⇒
        fsm ! msg
      case msg ⇒
        log.info("server {} sent garbage '{}', disconnecting", getAddrString(channel), msg)
        channel.close()
    }
  }
}


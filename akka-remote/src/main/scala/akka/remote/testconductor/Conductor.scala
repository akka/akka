/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.testconductor

import akka.actor.{ Actor, ActorRef, ActorSystem, LoggingFSM, Props }
import RemoteConnection.getAddrString
import TestConductorProtocol._
import org.jboss.netty.channel.{ Channel, SimpleChannelUpstreamHandler, ChannelHandlerContext, ChannelStateEvent, MessageEvent }
import com.typesafe.config.ConfigFactory
import akka.util.Timeout
import akka.util.Duration
import akka.util.duration._
import akka.pattern.ask
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.dispatch.Await
import akka.event.LoggingAdapter
import akka.actor.PoisonPill
import akka.event.Logging
import scala.util.control.NoStackTrace

object Conductor extends RunControl with FailureInject with BarrierSync {

  val system = ActorSystem("conductor", ConfigFactory.load().getConfig("conductor"))

  object Settings {
    val config = system.settings.config

    implicit val BarrierTimeout = Timeout(Duration(config.getMilliseconds("barrier-timeout"), MILLISECONDS))
    implicit val QueryTimeout = Timeout(Duration(config.getMilliseconds("query-timeout"), MILLISECONDS))
  }

  import Controller._

  private val controller = system.actorOf(Props[Controller], "controller")
  controller ! ClientConnected

  override def enter(name: String*) {
    import Settings.BarrierTimeout
    name foreach (b ⇒ Await.result(controller ? EnterBarrier(b), Duration.Inf))
  }

  override def throttle(node: String, target: String, direction: Direction, rateMBit: Float) {
    controller ! Throttle(node, target, direction, rateMBit)
  }

  override def blackhole(node: String, target: String, direction: Direction) {
    controller ! Throttle(node, target, direction, 0f)
  }

  override def disconnect(node: String, target: String) {
    controller ! Disconnect(node, target, false)
  }

  override def abort(node: String, target: String) {
    controller ! Disconnect(node, target, true)
  }

  override def shutdown(node: String, exitValue: Int) {
    controller ! Terminate(node, exitValue)
  }

  override def kill(node: String) {
    controller ! Terminate(node, -1)
  }

  override def getNodes = {
    import Settings.QueryTimeout
    Await.result(controller ? GetNodes mapTo manifest[List[String]], Duration.Inf)
  }

  override def removeNode(node: String) {
    controller ! Remove(node)
  }

}

class ConductorHandler(system: ActorSystem, controller: ActorRef, log: LoggingAdapter) extends SimpleChannelUpstreamHandler {

  var clients = Map[Channel, ActorRef]()

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val channel = event.getChannel
    log.debug("connection from {}", getAddrString(channel))
    val fsm = system.actorOf(Props(new ServerFSM(controller, channel)))
    clients += channel -> fsm
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val channel = event.getChannel
    log.debug("disconnect from {}", getAddrString(channel))
    val fsm = clients(channel)
    fsm ! PoisonPill
    clients -= channel
  }

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) = {
    val channel = event.getChannel
    log.debug("message from {}: {}", getAddrString(channel), event.getMessage)
    event.getMessage match {
      case msg: Wrapper if msg.getAllFields.size == 1 ⇒
        clients(channel) ! msg
      case msg ⇒
        log.info("client {} sent garbage '{}', disconnecting", getAddrString(channel), msg)
        channel.close()
    }
  }

}

object ServerFSM {
  sealed trait State
  case object Initial extends State
  case object Ready extends State

  case class Send(msg: Wrapper)
}

class ServerFSM(val controller: ActorRef, val channel: Channel) extends Actor with LoggingFSM[ServerFSM.State, Null] {
  import ServerFSM._
  import akka.actor.FSM._
  import Controller._

  startWith(Initial, null)

  when(Initial, stateTimeout = 10 seconds) {
    case Event(msg: Wrapper, _) ⇒
      if (msg.hasHello) {
        val hello = msg.getHello
        controller ! ClientConnected(hello.getName, hello.getHost, hello.getPort)
        goto(Ready)
      } else {
        log.warning("client {} sent no Hello in first message, disconnecting", getAddrString(channel))
        channel.close()
        stop()
      }
    case Event(StateTimeout, _) ⇒
      log.info("closing channel to {} because of Hello timeout", getAddrString(channel))
      channel.close()
      stop()
  }

  when(Ready) {
    case Event(msg: Wrapper, _) ⇒
      if (msg.hasBarrier) {
        val barrier = msg.getBarrier
        controller ! EnterBarrier(barrier.getName)
      } else {
        log.warning("client {} sent unsupported message {}", getAddrString(channel), msg)
      }
      stay
    case Event(Send(msg), _) ⇒
      channel.write(msg)
      stay
    case Event(EnterBarrier(name), _) ⇒
      val barrier = TestConductorProtocol.EnterBarrier.newBuilder.setName(name).build
      channel.write(Wrapper.newBuilder.setBarrier(barrier).build)
      stay
  }

  initialize
}

object Controller {
  case class ClientConnected(name: String, host: String, port: Int)
  case class ClientDisconnected(name: String)
  case object GetNodes

  case class NodeInfo(name: String, host: String, port: Int, fsm: ActorRef)
}

class Controller extends Actor {
  import Controller._

  val config = context.system.settings.config

  val host = config.getString("akka.testconductor.host")
  val port = config.getInt("akka.testconductor.port")
  val connection = RemoteConnection(Server, host, port,
    new ConductorHandler(context.system, self, Logging(context.system, "ConductorHandler")))

  val barrier = context.actorOf(Props[BarrierCoordinator], "barriers")
  var nodes = Map[String, NodeInfo]()

  override def receive = {
    case ClientConnected(name, host, port) ⇒
      nodes += name -> NodeInfo(name, host, port, sender)
      barrier forward ClientConnected
    case ClientConnected ⇒
      barrier forward ClientConnected
    case ClientDisconnected(name) ⇒
      nodes -= name
      barrier forward ClientDisconnected
    case e @ EnterBarrier(name) ⇒
      barrier forward e
    case Throttle(node, target, direction, rateMBit) ⇒
      val t = nodes(target)
      val throttle =
        InjectFailure.newBuilder
          .setFailure(FailType.Throttle)
          .setDirection(TestConductorProtocol.Direction.valueOf(direction.toString))
          .setHost(t.host)
          .setPort(t.port)
          .setRateMBit(rateMBit)
          .build
      nodes(node).fsm ! ServerFSM.Send(Wrapper.newBuilder.setFailure(throttle).build)
    case Disconnect(node, target, abort) ⇒
      val t = nodes(target)
      val disconnect =
        InjectFailure.newBuilder
          .setFailure(if (abort) FailType.Abort else FailType.Disconnect)
          .setHost(t.host)
          .setPort(t.port)
          .build
      nodes(node).fsm ! ServerFSM.Send(Wrapper.newBuilder.setFailure(disconnect).build)
    case Terminate(node, exitValueOrKill) ⇒
      if (exitValueOrKill < 0) {
        // TODO: kill via SBT
      } else {
        val shutdown = InjectFailure.newBuilder.setFailure(FailType.Shutdown).setExitValue(exitValueOrKill).build
        nodes(node).fsm ! ServerFSM.Send(Wrapper.newBuilder.setFailure(shutdown).build)
      }
    // TODO: properly remove node from BarrierCoordinator
    //    case Remove(node) =>
    //      nodes -= node
    case GetNodes ⇒ sender ! nodes.keys
  }
}

object BarrierCoordinator {
  sealed trait State
  case object Idle extends State
  case object Waiting extends State

  case class Data(clients: Int, barrier: String, arrived: List[ActorRef])
  class BarrierTimeoutException(msg: String) extends RuntimeException(msg) with NoStackTrace
}

class BarrierCoordinator extends Actor with LoggingFSM[BarrierCoordinator.State, BarrierCoordinator.Data] {
  import BarrierCoordinator._
  import akka.actor.FSM._
  import Controller._

  startWith(Idle, Data(0, "", Nil))

  when(Idle) {
    case Event(EnterBarrier(name), Data(num, _, _)) ⇒
      if (num == 0) throw new IllegalStateException("no client expected yet")
      goto(Waiting) using Data(num, name, sender :: Nil)
    case Event(ClientConnected, d @ Data(num, _, _)) ⇒
      stay using d.copy(clients = num + 1)
    case Event(ClientDisconnected, d @ Data(num, _, _)) ⇒
      if (num == 0) throw new IllegalStateException("no client to disconnect")
      stay using d.copy(clients = num - 1)
  }

  onTransition {
    case Idle -> Waiting ⇒ setTimer("Timeout", StateTimeout, 30 seconds, false)
    case Waiting -> Idle ⇒ cancelTimer("Timeout")
  }

  when(Waiting) {
    case Event(e @ EnterBarrier(name), d @ Data(num, barrier, arrived)) ⇒
      if (name != barrier) throw new IllegalStateException("trying enter barrier '" + name + "' while barrier '" + barrier + "' is active")
      val together = sender :: arrived
      if (together.size == num) {
        together foreach (_ ! e)
        goto(Idle) using Data(num, "", Nil)
      } else {
        stay using d.copy(arrived = together)
      }
    case Event(ClientConnected, d @ Data(num, _, _)) ⇒
      stay using d.copy(clients = num + 1)
    case Event(ClientDisconnected, d @ Data(num, barrier, arrived)) ⇒
      val expected = num - 1
      if (arrived.size == expected) {
        val e = EnterBarrier(barrier)
        sender :: arrived foreach (_ ! e)
        goto(Idle) using Data(expected, "", Nil)
      } else {
        stay using d.copy(clients = expected)
      }
    case Event(StateTimeout, Data(num, barrier, arrived)) ⇒
      throw new BarrierTimeoutException("only " + arrived.size + " of " + num + " arrived at barrier " + barrier)
  }

  initialize
}


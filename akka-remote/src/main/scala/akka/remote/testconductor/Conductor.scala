/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.testconductor

import akka.actor.{ Actor, ActorRef, LoggingFSM, Timeout, UntypedChannel }
import akka.event.EventHandler
import RemoteConnection.getAddrString
import akka.util.duration._
import TestConductorProtocol._
import akka.NoStackTrace
import org.jboss.netty.channel.{ Channel, SimpleChannelUpstreamHandler, ChannelHandlerContext, ChannelStateEvent, MessageEvent }

object Conductor extends RunControl with FailureInject with BarrierSync {

  import Controller._

  private val controller = Actor.actorOf[Controller]
  controller ! ClientConnected

  override def enter(name: String*) {
    implicit val timeout = Timeout(30 seconds)
    name foreach (b ⇒ (controller ? EnterBarrier(b)).get)
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

  override def getNodes = (controller ? GetNodes).as[List[String]].get

  override def removeNode(node: String) {
    controller ! Remove(node)
  }

}

class ConductorHandler(controller: ActorRef) extends SimpleChannelUpstreamHandler {

  var clients = Map[Channel, ActorRef]()

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val channel = event.getChannel
    EventHandler.debug(this, "connection from " + getAddrString(channel))
    val fsm = Actor.actorOf(new ServerFSM(controller, channel))
    clients += channel -> fsm
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val channel = event.getChannel
    EventHandler.debug(this, "disconnect from " + getAddrString(channel))
    val fsm = clients(channel)
    fsm.stop()
    clients -= channel
  }

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) = {
    val channel = event.getChannel
    EventHandler.debug(this, "message from " + getAddrString(channel) + ": " + event.getMessage)
    event.getMessage match {
      case msg: Wrapper if msg.getAllFields.size == 1 ⇒
        clients(channel) ! msg
      case msg ⇒
        EventHandler.info(this, "client " + getAddrString(channel) + " sent garbage '" + msg + "', disconnecting")
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
    case Ev(msg: Wrapper) ⇒
      if (msg.hasHello) {
        val hello = msg.getHello
        controller ! ClientConnected(hello.getName, hello.getHost, hello.getPort)
        goto(Ready)
      } else {
        EventHandler.warning(this, "client " + getAddrString(channel) + " sent no Hello in first message, disconnecting")
        channel.close()
        stop()
      }
    case Ev(StateTimeout) ⇒
      EventHandler.info(this, "closing channel to " + getAddrString(channel) + " because of Hello timeout")
      channel.close()
      stop()
  }

  when(Ready) {
    case Ev(msg: Wrapper) ⇒
      if (msg.hasBarrier) {
        val barrier = msg.getBarrier
        controller ! EnterBarrier(barrier.getName)
      } else {
        EventHandler.warning(this, "client " + getAddrString(channel) + " sent unsupported message " + msg)
      }
      stay
    case Ev(Send(msg)) ⇒
      channel.write(msg)
      stay
    case Ev(EnterBarrier(name)) ⇒
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

  val host = System.getProperty("akka.testconductor.host", "localhost")
  val port = Integer.getInteger("akka.testconductor.port", 4545)
  val connection = RemoteConnection(Server, host, port, new ConductorHandler(self))

  val barrier = Actor.actorOf[BarrierCoordinator]
  var nodes = Map[String, NodeInfo]()

  override def receive = Actor.loggable(this) {
    case ClientConnected(name, host, port) ⇒
      self.channel match {
        case ref: ActorRef ⇒ nodes += name -> NodeInfo(name, host, port, ref)
      }
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
    case GetNodes ⇒ self reply nodes.keys
  }
}

object BarrierCoordinator {
  sealed trait State
  case object Idle extends State
  case object Waiting extends State

  case class Data(clients: Int, barrier: String, arrived: List[UntypedChannel])
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
      goto(Waiting) using Data(num, name, self.channel :: Nil)
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
      val together = self.channel :: arrived
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
        self.channel :: arrived foreach (_ ! e)
        goto(Idle) using Data(expected, "", Nil)
      } else {
        stay using d.copy(clients = expected)
      }
    case Event(StateTimeout, Data(num, barrier, arrived)) ⇒
      throw new BarrierTimeoutException("only " + arrived.size + " of " + num + " arrived at barrier " + barrier)
  }

  initialize
}


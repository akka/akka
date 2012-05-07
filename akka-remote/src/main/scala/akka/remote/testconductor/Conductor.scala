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
import akka.event.LoggingReceive
import akka.actor.Address
import java.net.InetSocketAddress
import akka.dispatch.Future

trait Conductor extends RunControl with FailureInject { this: TestConductorExt ⇒

  import Controller._

  private var _controller: ActorRef = _
  private def controller: ActorRef = _controller match {
    case null ⇒ throw new RuntimeException("TestConductorServer was not started")
    case x    ⇒ x
  }

  override def startController(participants: Int): Future[Int] = {
    if (_controller ne null) throw new RuntimeException("TestConductorServer was already started")
    _controller = system.actorOf(Props(new Controller(participants)), "controller")
    import Settings.BarrierTimeout
    controller ? GetPort flatMap { case port: Int ⇒ startClient(port) map (_ ⇒ port) }
  }

  override def port: Future[Int] = {
    import Settings.QueryTimeout
    controller ? GetPort mapTo
  }

  override def throttle(node: String, target: String, direction: Direction, rateMBit: Double): Future[Done] = {
    import Settings.QueryTimeout
    controller ? Throttle(node, target, direction, rateMBit.toFloat) mapTo
  }

  override def blackhole(node: String, target: String, direction: Direction): Future[Done] = {
    import Settings.QueryTimeout
    controller ? Throttle(node, target, direction, 0f) mapTo
  }

  override def disconnect(node: String, target: String): Future[Done] = {
    import Settings.QueryTimeout
    controller ? Disconnect(node, target, false) mapTo
  }

  override def abort(node: String, target: String): Future[Done] = {
    import Settings.QueryTimeout
    controller ? Disconnect(node, target, true) mapTo
  }

  override def shutdown(node: String, exitValue: Int): Future[Done] = {
    import Settings.QueryTimeout
    controller ? Terminate(node, exitValue) mapTo
  }

  override def kill(node: String): Future[Done] = {
    import Settings.QueryTimeout
    controller ? Terminate(node, -1) mapTo
  }

  override def getNodes: Future[List[String]] = {
    import Settings.QueryTimeout
    controller ? GetNodes mapTo
  }

  override def removeNode(node: String): Future[Done] = {
    import Settings.QueryTimeout
    controller ? Remove(node) mapTo
  }

}

class ConductorHandler(system: ActorSystem, controller: ActorRef, log: LoggingAdapter) extends SimpleChannelUpstreamHandler {

  @volatile
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
      case msg: NetworkOp ⇒
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
}

class ServerFSM(val controller: ActorRef, val channel: Channel) extends Actor with LoggingFSM[ServerFSM.State, Option[ActorRef]] {
  import ServerFSM._
  import akka.actor.FSM._
  import Controller._

  startWith(Initial, None)

  when(Initial, stateTimeout = 10 seconds) {
    case Event(Hello(name, addr), _) ⇒
      controller ! ClientConnected(name, addr)
      goto(Ready)
    case Event(x: NetworkOp, _) ⇒
      log.warning("client {} sent no Hello in first message (instead {}), disconnecting", getAddrString(channel), x)
      channel.close()
      stop()
    case Event(Send(msg), _) ⇒
      log.warning("cannot send {} in state Initial", msg)
      stay
    case Event(StateTimeout, _) ⇒
      log.info("closing channel to {} because of Hello timeout", getAddrString(channel))
      channel.close()
      stop()
  }

  when(Ready) {
    case Event(msg: EnterBarrier, _) ⇒
      controller ! msg
      stay
    case Event(d: Done, Some(s)) ⇒
      s ! d
      stay using None
    case Event(msg: NetworkOp, _) ⇒
      log.warning("client {} sent unsupported message {}", getAddrString(channel), msg)
      channel.close()
      stop()
    case Event(Send(msg @ (_: EnterBarrier | _: Done)), _) ⇒
      channel.write(msg)
      stay
    case Event(Send(msg), None) ⇒
      channel.write(msg)
      stay using Some(sender)
    case Event(Send(msg), _) ⇒
      log.warning("cannot send {} while waiting for previous ACK", msg)
      stay
  }

  initialize
}

object Controller {
  case class ClientConnected(name: String, address: Address)
  case class ClientDisconnected(name: String)
  case object GetNodes
  case object GetPort

  case class NodeInfo(name: String, addr: Address, fsm: ActorRef)
}

class Controller(_participants: Int) extends Actor {
  import Controller._

  var initialParticipants = _participants

  val settings = TestConductor().Settings
  val connection = RemoteConnection(Server, settings.host, settings.port,
    new ConductorHandler(context.system, self, Logging(context.system, "ConductorHandler")))

  val barrier = context.actorOf(Props[BarrierCoordinator], "barriers")
  var nodes = Map[String, NodeInfo]()

  override def receive = LoggingReceive {
    case ClientConnected(name, addr) ⇒
      nodes += name -> NodeInfo(name, addr, sender)
      barrier forward ClientConnected
      if (initialParticipants <= 0) sender ! Done
      else if (nodes.size == initialParticipants) {
        for (NodeInfo(_, _, client) ← nodes.values) client ! Send(Done)
        initialParticipants = 0
      }
    case ClientDisconnected(name) ⇒
      nodes -= name
      barrier forward ClientDisconnected
    case e @ EnterBarrier(name) ⇒
      barrier forward e
    case Throttle(node, target, direction, rateMBit) ⇒
      val t = nodes(target)
      nodes(node).fsm forward Send(ThrottleMsg(t.addr, direction, rateMBit))
    case Disconnect(node, target, abort) ⇒
      val t = nodes(target)
      nodes(node).fsm forward Send(DisconnectMsg(t.addr, abort))
    case Terminate(node, exitValueOrKill) ⇒
      if (exitValueOrKill < 0) {
        // TODO: kill via SBT
      } else {
        nodes(node).fsm forward Send(TerminateMsg(exitValueOrKill))
      }
    // TODO: properly remove node from BarrierCoordinator
    //    case Remove(node) =>
    //      nodes -= node
    case GetNodes ⇒ sender ! nodes.keys
    case GetPort ⇒
      sender ! (connection.getLocalAddress match {
        case inet: InetSocketAddress ⇒ inet.getPort
      })
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
        together foreach (_ ! Send(e))
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
        sender :: arrived foreach (_ ! Send(e))
        goto(Idle) using Data(expected, "", Nil)
      } else {
        stay using d.copy(clients = expected)
      }
    case Event(StateTimeout, Data(num, barrier, arrived)) ⇒
      throw new BarrierTimeoutException("only " + arrived.size + " of " + num + " arrived at barrier " + barrier)
  }

  initialize
}


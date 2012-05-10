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
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy
import java.util.concurrent.ConcurrentHashMap
import akka.actor.Status

sealed trait Direction

object Direction {
  case object Send extends Direction
  case object Receive extends Direction
  case object Both extends Direction
}

/**
 * The conductor is the one orchestrating the test: it governs the
 * [[akka.remote.testconductor.Controller]]’s port to which all
 * [[akka.remote.testconductor.Player]]s connect, it issues commands to their
 * [[akka.remote.testconductor.NetworkFailureInjector]] and provides support
 * for barriers using the [[akka.remote.testconductor.BarrierCoordinator]].
 * All of this is bundled inside the [[akka.remote.testconductor.TestConductorExt]]
 * extension.
 */
trait Conductor { this: TestConductorExt ⇒

  import Controller._

  private var _controller: ActorRef = _
  private def controller: ActorRef = _controller match {
    case null ⇒ throw new RuntimeException("TestConductorServer was not started")
    case x    ⇒ x
  }

  /**
   * Start the [[akka.remote.testconductor.Controller]], which in turn will
   * bind to a TCP port as specified in the `akka.testconductor.port` config
   * property, where 0 denotes automatic allocation. Since the latter is
   * actually preferred, a `Future[Int]` is returned which will be completed
   * with the port number actually chosen, so that this can then be communicated
   * to the players for their proper start-up.
   *
   * This method also invokes [[akka.remote.testconductor.Player]].startClient,
   * since it is expected that the conductor participates in barriers for
   * overall coordination. The returned Future will only be completed once the
   * client’s start-up finishes, which in fact waits for all other players to
   * connect.
   *
   * @param participants gives the number of participants which shall connect
   * before any of their startClient() operations complete.
   */
  def startController(participants: Int): Future[Int] = {
    if (_controller ne null) throw new RuntimeException("TestConductorServer was already started")
    _controller = system.actorOf(Props(new Controller(participants)), "controller")
    import Settings.BarrierTimeout
    controller ? GetPort flatMap { case port: Int ⇒ startClient(port) map (_ ⇒ port) }
  }

  /**
   * Obtain the port to which the controller’s socket is actually bound. This
   * will deviate from the configuration in `akka.testconductor.port` in case
   * that was given as zero.
   */
  def port: Future[Int] = {
    import Settings.QueryTimeout
    controller ? GetPort mapTo
  }

  /**
   * Make the remoting pipeline on the node throttle data sent to or received
   * from the given remote peer. Throttling works by delaying packet submission
   * within the netty pipeline until the packet would have been completely sent
   * according to the given rate, the previous packet completion and the current
   * packet length. In case of large packets they are split up if the calculated
   * send pause would exceed `akka.testconductor.packet-split-threshold`
   * (roughly). All of this uses the system’s HashedWheelTimer, which is not
   * terribly precise and will execute tasks later than they are schedule (even
   * on average), but that is countered by using the actual execution time for
   * determining how much to send, leading to the correct output rate, but with
   * increased latency.
   * 
   * @param node is the symbolic name of the node which is to be affected
   * @param target is the symbolic name of the other node to which connectivity shall be throttled
   * @param direction can be either `Direction.Send`, `Direction.Receive` or `Direction.Both`
   * @param rateMBit is the maximum data rate in MBit
   */
  def throttle(node: String, target: String, direction: Direction, rateMBit: Double): Future[Done] = {
    import Settings.QueryTimeout
    controller ? Throttle(node, target, direction, rateMBit.toFloat) mapTo
  }

  /**
   * Switch the Netty pipeline of the remote support into blackhole mode for
   * sending and/or receiving: it will just drop all messages right before
   * submitting them to the Socket or right after receiving them from the
   * Socket.
   * 
   * @param node is the symbolic name of the node which is to be affected
   * @param target is the symbolic name of the other node to which connectivity shall be impeded
   * @param direction can be either `Direction.Send`, `Direction.Receive` or `Direction.Both`
   */
  def blackhole(node: String, target: String, direction: Direction): Future[Done] = {
    import Settings.QueryTimeout
    controller ? Throttle(node, target, direction, 0f) mapTo
  }

  /**
   * Tell the remote support to shutdown the connection to the given remote
   * peer. It works regardless of whether the recipient was initiator or
   * responder.
   * 
   * @param node is the symbolic name of the node which is to be affected
   * @param target is the symbolic name of the other node to which connectivity shall be impeded
   */
  def disconnect(node: String, target: String): Future[Done] = {
    import Settings.QueryTimeout
    controller ? Disconnect(node, target, false) mapTo
  }

  /**
   * Tell the remote support to TCP_RESET the connection to the given remote
   * peer. It works regardless of whether the recipient was initiator or
   * responder.
   * 
   * @param node is the symbolic name of the node which is to be affected
   * @param target is the symbolic name of the other node to which connectivity shall be impeded
   */
  def abort(node: String, target: String): Future[Done] = {
    import Settings.QueryTimeout
    controller ? Disconnect(node, target, true) mapTo
  }

  /**
   * Tell the remote node to shut itself down using System.exit with the given
   * exitValue.
   * 
   * @param node is the symbolic name of the node which is to be affected
   * @param exitValue is the return code which shall be given to System.exit
   */
  def shutdown(node: String, exitValue: Int): Future[Done] = {
    import Settings.QueryTimeout
    controller ? Terminate(node, exitValue) mapTo
  }

  /**
   * Tell the SBT plugin to forcibly terminate the given remote node using Process.destroy.
   * 
   * @param node is the symbolic name of the node which is to be affected
   */
  def kill(node: String): Future[Done] = {
    import Settings.QueryTimeout
    controller ? Terminate(node, -1) mapTo
  }

  /**
   * Obtain the list of remote host names currently registered.
   */
  def getNodes: Future[List[String]] = {
    import Settings.QueryTimeout
    controller ? GetNodes mapTo
  }

  /**
   * Remove a remote host from the list, so that the remaining nodes may still
   * pass subsequent barriers. This must be done before the client connection
   * breaks down in order to affect an “orderly” removal (i.e. without failing
   * present and future barriers).
   * 
   * @param node is the symbolic name of the node which is to be removed
   */
  def removeNode(node: String): Future[Done] = {
    import Settings.QueryTimeout
    controller ? Remove(node) mapTo
  }

}

/**
 * This handler is installed at the end of the controller’s netty pipeline. Its only
 * purpose is to dispatch incoming messages to the right ServerFSM actor. There is
 * one shared instance of this class for all connections accepted by one Controller.
 */
class ConductorHandler(system: ActorSystem, controller: ActorRef, log: LoggingAdapter) extends SimpleChannelUpstreamHandler {

  val clients = new ConcurrentHashMap[Channel, ActorRef]()

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val channel = event.getChannel
    log.debug("connection from {}", getAddrString(channel))
    val fsm = system.actorOf(Props(new ServerFSM(controller, channel)))
    clients.put(channel, fsm)
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val channel = event.getChannel
    log.debug("disconnect from {}", getAddrString(channel))
    val fsm = clients.get(channel)
    fsm ! Controller.ClientDisconnected
    clients.remove(channel)
  }

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) = {
    val channel = event.getChannel
    log.debug("message from {}: {}", getAddrString(channel), event.getMessage)
    event.getMessage match {
      case msg: NetworkOp ⇒
        clients.get(channel) ! msg
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

/**
 * The server part of each client connection is represented by a ServerFSM.
 * The Initial state handles reception of the new client’s
 * [[akka.remote.testconductor.Hello]] message (which is needed for all subsequent
 * node name translations).
 *
 * In the Ready state, messages from the client are forwarded to the controller
 * and [[akka.remote.testconductor.Send]] requests are sent, but the latter is
 * treated specially: all client operations are to be confirmed by a
 * [[akka.remote.testconductor.Done]] message, and there can be only one such
 * request outstanding at a given time (i.e. a Send fails if the previous has
 * not yet been acknowledged).
 */
class ServerFSM(val controller: ActorRef, val channel: Channel) extends Actor with LoggingFSM[ServerFSM.State, Option[ActorRef]] {
  import ServerFSM._
  import akka.actor.FSM._
  import Controller._

  startWith(Initial, None)

  whenUnhandled {
    case Event(ClientDisconnected, Some(s)) ⇒
      s ! Status.Failure(new RuntimeException("client disconnected in state " + stateName + ": " + channel))
      stop()
    case Event(ClientDisconnected, None) ⇒ stop()
  }

  onTermination {
    case _ ⇒ controller ! ClientDisconnected
  }

  when(Initial, stateTimeout = 10 seconds) {
    case Event(Hello(name, addr), _) ⇒
      controller ! NodeInfo(name, addr, self)
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

  onTermination {
    case _ ⇒ channel.close()
  }
}

object Controller {
  case class ClientDisconnected(name: String)
  case object GetNodes
  case object GetPort

  case class NodeInfo(name: String, addr: Address, fsm: ActorRef)
}

/**
 * This controls test execution by managing barriers (delegated to
 * [[akka.remote.testconductor.BarrierCoordinator]], its child) and allowing
 * network and other failures to be injected at the test nodes.
 */
class Controller(_participants: Int) extends Actor {
  import Controller._

  var initialParticipants = _participants

  val settings = TestConductor().Settings
  val connection = RemoteConnection(Server, settings.host, settings.port,
    new ConductorHandler(context.system, self, Logging(context.system, "ConductorHandler")))

  override def supervisorStrategy = OneForOneStrategy() {
    case e: BarrierCoordinator.BarrierTimeoutException ⇒ SupervisorStrategy.Resume
    case e: BarrierCoordinator.WrongBarrierException ⇒
      for (NodeInfo(c, _, _) ← e.data.clients; info ← nodes get c)
        barrier ! NodeInfo(c, info.addr, info.fsm)
      for (c ← e.data.arrived) c ! BarrierFailed(e.barrier)
      SupervisorStrategy.Restart
  }

  val barrier = context.actorOf(Props[BarrierCoordinator], "barriers")
  var nodes = Map[String, NodeInfo]()

  override def receive = LoggingReceive {
    case c @ NodeInfo(name, addr, fsm) ⇒
      nodes += name -> c
      barrier forward c
      if (initialParticipants <= 0) sender ! Done
      else if (nodes.size == initialParticipants) {
        for (NodeInfo(_, _, client) ← nodes.values) client ! Send(Done)
        initialParticipants = 0
      }
    case c @ ClientDisconnected(name) ⇒
      nodes -= name
      barrier forward c
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
    case Remove(node) ⇒
      nodes -= node
      barrier ! BarrierCoordinator.RemoveClient(node)
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

  case class RemoveClient(name: String)

  case class Data(clients: Set[Controller.NodeInfo], barrier: String, arrived: List[ActorRef])
  class BarrierTimeoutException(val data: Data) extends RuntimeException(data.barrier) with NoStackTrace
  class WrongBarrierException(val barrier: String, val client: ActorRef, val data: Data) extends RuntimeException(barrier) with NoStackTrace
}

/**
 * This barrier coordinator gets informed of players connecting (NodeInfo),
 * players being deliberately removed (RemoveClient) or failing (ClientDisconnected)
 * by the controller. It also receives EnterBarrier requests, where upon the first
 * one received the name of the current barrier is set and all other known clients
 * are expected to join the barrier, whereupon all of the will be sent the successful
 * EnterBarrier return message. In case of planned removals, this may just happen
 * earlier, in case of failures the current barrier (and all subsequent ones) will
 * be failed by sending BarrierFailed responses.
 */
class BarrierCoordinator extends Actor with LoggingFSM[BarrierCoordinator.State, BarrierCoordinator.Data] {
  import BarrierCoordinator._
  import akka.actor.FSM._
  import Controller._

  // this shall be set to false if all subsequent barriers shall fail
  var failed = false
  override def preRestart(reason: Throwable, message: Option[Any]) {}
  override def postRestart(reason: Throwable) { failed = true }

  // TODO what happens with the other waiting players in case of a test failure?

  startWith(Idle, Data(Set(), "", Nil))

  whenUnhandled {
    case Event(n: NodeInfo, d @ Data(clients, _, _)) ⇒
      stay using d.copy(clients = clients + n)
  }

  when(Idle) {
    case Event(EnterBarrier(name), d @ Data(clients, _, _)) ⇒
      if (clients.isEmpty) throw new IllegalStateException("no client expected yet")
      if (failed)
        stay replying BarrierFailed(name)
      else
        goto(Waiting) using d.copy(barrier = name, arrived = sender :: Nil)
    case Event(ClientDisconnected(name), d @ Data(clients, _, _)) ⇒
      if (clients.isEmpty) throw new IllegalStateException("no client to disconnect")
      (clients filterNot (_.name == name)) match {
        case `clients` ⇒ stay
        case c ⇒
          failed = true
          stay using d.copy(clients = c)
      }
    case Event(RemoveClient(name), d @ Data(clients, _, _)) ⇒
      if (clients.isEmpty) throw new IllegalStateException("no client to remove")
      stay using d.copy(clients = clients filterNot (_.name == name))
  }

  onTransition {
    case Idle -> Waiting ⇒ setTimer("Timeout", StateTimeout, TestConductor().Settings.BarrierTimeout.duration, false)
    case Waiting -> Idle ⇒ cancelTimer("Timeout")
  }

  when(Waiting) {
    case Event(e @ EnterBarrier(name), d @ Data(clients, barrier, arrived)) ⇒
      if (name != barrier) throw new WrongBarrierException(barrier, sender, d)
      val together = sender :: arrived
      handleBarrier(d.copy(arrived = together))
    case Event(RemoveClient(name), d @ Data(clients, barrier, arrived)) ⇒
      val newClients = clients filterNot (_.name == name)
      val newArrived = arrived filterNot (_ == name)
      handleBarrier(d.copy(clients = newClients, arrived = newArrived))
    case Event(ClientDisconnected(name), d @ Data(clients, barrier, arrived)) ⇒
      (clients filterNot (_.name == name)) match {
        case `clients` ⇒ stay
        case c ⇒
          val f = BarrierFailed(barrier)
          arrived foreach (_ ! Send(f))
          failed = true
          goto(Idle) using Data(c, "", Nil)
      }
    case Event(StateTimeout, data) ⇒
      throw new BarrierTimeoutException(data)
  }

  initialize

  def handleBarrier(data: Data): State =
    if ((data.clients.map(_.fsm) -- data.arrived).isEmpty) {
      val e = EnterBarrier(data.barrier)
      data.arrived foreach (_ ! Send(e))
      goto(Idle) using data.copy(barrier = "", arrived = Nil)
    } else {
      stay using data
    }

}


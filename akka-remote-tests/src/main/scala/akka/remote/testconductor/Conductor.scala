/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.testconductor

import language.postfixOps
import akka.actor.{ Actor, ActorRef, ActorSystem, LoggingFSM, Props }
import RemoteConnection.getAddrString
import TestConductorProtocol._
import org.jboss.netty.channel.{ Channel, SimpleChannelUpstreamHandler, ChannelHandlerContext, ChannelStateEvent, MessageEvent }
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._
import akka.pattern.ask
import scala.concurrent.Await
import akka.event.{ LoggingAdapter, Logging }
import scala.util.control.NoStackTrace
import akka.event.LoggingReceive
import java.net.InetSocketAddress
import scala.concurrent.Future
import akka.actor.{ OneForOneStrategy, SupervisorStrategy, Status, Address, PoisonPill }
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.util.{ Timeout }
import scala.reflect.classTag
import akka.ConfigurationException
import akka.AkkaException
import akka.remote.transport.ThrottlerTransportAdapter.Direction

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
    case null ⇒ throw new IllegalStateException("TestConductorServer was not started")
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
  def startController(participants: Int, name: RoleName, controllerPort: InetSocketAddress): Future[InetSocketAddress] = {
    if (_controller ne null) throw new RuntimeException("TestConductorServer was already started")
    _controller = system.actorOf(Props(new Controller(participants, controllerPort)), "controller")
    import Settings.BarrierTimeout
    import system.dispatcher
    controller ? GetSockAddr flatMap { case sockAddr: InetSocketAddress ⇒ startClient(name, sockAddr) map (_ ⇒ sockAddr) }
  }

  /**
   * Obtain the port to which the controller’s socket is actually bound. This
   * will deviate from the configuration in `akka.testconductor.port` in case
   * that was given as zero.
   */
  def sockAddr: Future[InetSocketAddress] = {
    import Settings.QueryTimeout
    controller ? GetSockAddr mapTo classTag[InetSocketAddress]
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
   * ====Note====
   * To use this feature you must activate the `TestConductorTranport`
   * by specifying `testTransport(on = true)` in your MultiNodeConfig.
   *
   * @param node is the symbolic name of the node which is to be affected
   * @param target is the symbolic name of the other node to which connectivity shall be throttled
   * @param direction can be either `Direction.Send`, `Direction.Receive` or `Direction.Both`
   * @param rateMBit is the maximum data rate in MBit
   */
  def throttle(node: RoleName, target: RoleName, direction: Direction, rateMBit: Double): Future[Done] = {
    import Settings.QueryTimeout
    requireTestConductorTranport()
    controller ? Throttle(node, target, direction, rateMBit.toFloat) mapTo classTag[Done]
  }

  /**
   * Switch the Netty pipeline of the remote support into blackhole mode for
   * sending and/or receiving: it will just drop all messages right before
   * submitting them to the Socket or right after receiving them from the
   * Socket.
   *
   * ====Note====
   * To use this feature you must activate the `TestConductorTranport`
   * by specifying `testTransport(on = true)` in your MultiNodeConfig.
   *
   * @param node is the symbolic name of the node which is to be affected
   * @param target is the symbolic name of the other node to which connectivity shall be impeded
   * @param direction can be either `Direction.Send`, `Direction.Receive` or `Direction.Both`
   */
  def blackhole(node: RoleName, target: RoleName, direction: Direction): Future[Done] = {
    import Settings.QueryTimeout
    requireTestConductorTranport()
    controller ? Throttle(node, target, direction, 0f) mapTo classTag[Done]
  }

  private def requireTestConductorTranport(): Unit = if (!transport.defaultAddress.protocol.contains(".gremlin.trttl."))
    throw new ConfigurationException("To use this feature you must activate the failure injector adapters " +
      "(gremlin, trttl) by specifying `testTransport(on = true)` in your MultiNodeConfig.")

  /**
   * Switch the Netty pipeline of the remote support into pass through mode for
   * sending and/or receiving.
   *
   * ====Note====
   * To use this feature you must activate the `TestConductorTranport`
   * by specifying `testTransport(on = true)` in your MultiNodeConfig.
   *
   * @param node is the symbolic name of the node which is to be affected
   * @param target is the symbolic name of the other node to which connectivity shall be impeded
   * @param direction can be either `Direction.Send`, `Direction.Receive` or `Direction.Both`
   */
  def passThrough(node: RoleName, target: RoleName, direction: Direction): Future[Done] = {
    import Settings.QueryTimeout
    requireTestConductorTranport()
    controller ? Throttle(node, target, direction, -1f) mapTo classTag[Done]
  }

  /**
   * Tell the remote support to shutdown the connection to the given remote
   * peer. It works regardless of whether the recipient was initiator or
   * responder.
   *
   * @param node is the symbolic name of the node which is to be affected
   * @param target is the symbolic name of the other node to which connectivity shall be impeded
   */
  def disconnect(node: RoleName, target: RoleName): Future[Done] = {
    import Settings.QueryTimeout
    controller ? Disconnect(node, target, false) mapTo classTag[Done]
  }

  /**
   * Tell the remote support to TCP_RESET the connection to the given remote
   * peer. It works regardless of whether the recipient was initiator or
   * responder.
   *
   * @param node is the symbolic name of the node which is to be affected
   * @param target is the symbolic name of the other node to which connectivity shall be impeded
   */
  def abort(node: RoleName, target: RoleName): Future[Done] = {
    import Settings.QueryTimeout
    controller ? Disconnect(node, target, true) mapTo classTag[Done]
  }

  /**
   * Tell the remote node to shut itself down using System.exit with the given
   * exitValue. The node will also be removed, so that the remaining nodes may still
   * pass subsequent barriers.
   *
   * @param node is the symbolic name of the node which is to be affected
   * @param exitValue is the return code which shall be given to System.exit
   */
  def shutdown(node: RoleName, exitValue: Int): Future[Done] = {
    import Settings.QueryTimeout
    import system.dispatcher
    // the recover is needed to handle ClientDisconnectedException exception,
    // which is normal during shutdown
    controller ? Terminate(node, exitValue) mapTo classTag[Done] recover { case _: ClientDisconnectedException ⇒ Done }
  }

  /**
   * Obtain the list of remote host names currently registered.
   */
  def getNodes: Future[Iterable[RoleName]] = {
    import Settings.QueryTimeout
    controller ? GetNodes mapTo classTag[Iterable[RoleName]]
  }

  /**
   * Remove a remote host from the list, so that the remaining nodes may still
   * pass subsequent barriers. This must be done before the client connection
   * breaks down in order to affect an “orderly” removal (i.e. without failing
   * present and future barriers).
   *
   * @param node is the symbolic name of the node which is to be removed
   */
  def removeNode(node: RoleName): Future[Done] = {
    import Settings.QueryTimeout
    controller ? Remove(node) mapTo classTag[Done]
  }

}

/**
 * This handler is installed at the end of the controller’s netty pipeline. Its only
 * purpose is to dispatch incoming messages to the right ServerFSM actor. There is
 * one shared instance of this class for all connections accepted by one Controller.
 *
 * INTERNAL API.
 */
private[akka] class ConductorHandler(_createTimeout: Timeout, controller: ActorRef, log: LoggingAdapter) extends SimpleChannelUpstreamHandler {

  implicit val createTimeout = _createTimeout
  val clients = new ConcurrentHashMap[Channel, ActorRef]()

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val channel = event.getChannel
    log.debug("connection from {}", getAddrString(channel))
    val fsm: ActorRef = Await.result(controller ? Controller.CreateServerFSM(channel) mapTo classTag[ActorRef], Duration.Inf)
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

/**
 * INTERNAL API.
 */
private[akka] object ServerFSM {
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
 *
 * INTERNAL API.
 */
private[akka] class ServerFSM(val controller: ActorRef, val channel: Channel) extends Actor with LoggingFSM[ServerFSM.State, Option[ActorRef]] {
  import ServerFSM._
  import akka.actor.FSM._
  import Controller._

  var roleName: RoleName = null

  startWith(Initial, None)

  whenUnhandled {
    case Event(ClientDisconnected, Some(s)) ⇒
      s ! Status.Failure(new ClientDisconnectedException("client disconnected in state " + stateName + ": " + channel))
      stop()
    case Event(ClientDisconnected, None) ⇒ stop()
  }

  onTermination {
    case _ ⇒
      controller ! ClientDisconnected(roleName)
      channel.close()
  }

  when(Initial, stateTimeout = 10 seconds) {
    case Event(Hello(name, addr), _) ⇒
      roleName = RoleName(name)
      controller ! NodeInfo(roleName, addr, self)
      goto(Ready)
    case Event(x: NetworkOp, _) ⇒
      log.warning("client {} sent no Hello in first message (instead {}), disconnecting", getAddrString(channel), x)
      channel.close()
      stop()
    case Event(ToClient(msg), _) ⇒
      log.warning("cannot send {} in state Initial", msg)
      stay
    case Event(StateTimeout, _) ⇒
      log.info("closing channel to {} because of Hello timeout", getAddrString(channel))
      channel.close()
      stop()
  }

  when(Ready) {
    case Event(d: Done, Some(s)) ⇒
      s ! d
      stay using None
    case Event(op: ServerOp, _) ⇒
      controller ! op
      stay
    case Event(msg: NetworkOp, _) ⇒
      log.warning("client {} sent unsupported message {}", getAddrString(channel), msg)
      stop()
    case Event(ToClient(msg: UnconfirmedClientOp), _) ⇒
      channel.write(msg)
      stay
    case Event(ToClient(msg), None) ⇒
      channel.write(msg)
      stay using Some(sender)
    case Event(ToClient(msg), _) ⇒
      log.warning("cannot send {} while waiting for previous ACK", msg)
      stay
  }

  initialize
}

/**
 * INTERNAL API.
 */
private[akka] object Controller {
  case class ClientDisconnected(name: RoleName)
  class ClientDisconnectedException(msg: String) extends AkkaException(msg)
  case object GetNodes
  case object GetSockAddr
  case class CreateServerFSM(channel: Channel)

  case class NodeInfo(name: RoleName, addr: Address, fsm: ActorRef)
}

/**
 * This controls test execution by managing barriers (delegated to
 * [[akka.remote.testconductor.BarrierCoordinator]], its child) and allowing
 * network and other failures to be injected at the test nodes.
 *
 * INTERNAL API.
 */
private[akka] class Controller(private var initialParticipants: Int, controllerPort: InetSocketAddress) extends Actor {
  import Controller._
  import BarrierCoordinator._

  val settings = TestConductor().Settings
  val connection = RemoteConnection(Server, controllerPort, settings.ServerSocketWorkerPoolSize,
    new ConductorHandler(settings.QueryTimeout, self, Logging(context.system, "ConductorHandler")))

  /*
   * Supervision of the BarrierCoordinator means to catch all his bad emotions
   * and sometimes console him (BarrierEmpty, BarrierTimeout), sometimes tell
   * him to hate the world (WrongBarrier, DuplicateNode, ClientLost). The latter shall help
   * terminate broken tests as quickly as possible (i.e. without awaiting
   * BarrierTimeouts in the players).
   */
  override def supervisorStrategy = OneForOneStrategy() {
    case BarrierTimeout(data)             ⇒ failBarrier(data)
    case FailedBarrier(data)              ⇒ failBarrier(data)
    case BarrierEmpty(data, msg)          ⇒ SupervisorStrategy.Resume
    case WrongBarrier(name, client, data) ⇒ client ! ToClient(BarrierResult(name, false)); failBarrier(data)
    case ClientLost(data, node)           ⇒ failBarrier(data)
    case DuplicateNode(data, node)        ⇒ failBarrier(data)
  }

  def failBarrier(data: Data): SupervisorStrategy.Directive = {
    for (c ← data.arrived) c ! ToClient(BarrierResult(data.barrier, false))
    SupervisorStrategy.Restart
  }

  val barrier = context.actorOf(Props[BarrierCoordinator], "barriers")
  var nodes = Map[RoleName, NodeInfo]()

  // map keeping unanswered queries for node addresses (enqueued upon GetAddress, serviced upon NodeInfo)
  var addrInterest = Map[RoleName, Set[ActorRef]]()
  val generation = Iterator from 1

  override def receive = LoggingReceive {
    case CreateServerFSM(channel) ⇒
      val (ip, port) = channel.getRemoteAddress match { case s: InetSocketAddress ⇒ (s.getAddress.getHostAddress, s.getPort) }
      val name = ip + ":" + port + "-server" + generation.next
      sender ! context.actorOf(Props(new ServerFSM(self, channel)), name)
    case c @ NodeInfo(name, addr, fsm) ⇒
      barrier forward c
      if (nodes contains name) {
        if (initialParticipants > 0) {
          for (NodeInfo(_, _, client) ← nodes.values) client ! ToClient(BarrierResult("initial startup", false))
          initialParticipants = 0
        }
        fsm ! ToClient(BarrierResult("initial startup", false))
      } else {
        nodes += name -> c
        if (initialParticipants <= 0) fsm ! ToClient(Done)
        else if (nodes.size == initialParticipants) {
          for (NodeInfo(_, _, client) ← nodes.values) client ! ToClient(Done)
          initialParticipants = 0
        }
        if (addrInterest contains name) {
          addrInterest(name) foreach (_ ! ToClient(AddressReply(name, addr)))
          addrInterest -= name
        }
      }
    case c @ ClientDisconnected(name) ⇒
      nodes -= name
      barrier forward c
    case op: ServerOp ⇒
      op match {
        case _: EnterBarrier ⇒ barrier forward op
        case _: FailBarrier  ⇒ barrier forward op
        case GetAddress(node) ⇒
          if (nodes contains node) sender ! ToClient(AddressReply(node, nodes(node).addr))
          else addrInterest += node -> ((addrInterest get node getOrElse Set()) + sender)
        case _: Done ⇒ //FIXME what should happen?
      }
    case op: CommandOp ⇒
      op match {
        case Throttle(node, target, direction, rateMBit) ⇒
          val t = nodes(target)
          nodes(node).fsm forward ToClient(ThrottleMsg(t.addr, direction, rateMBit))
        case Disconnect(node, target, abort) ⇒
          val t = nodes(target)
          nodes(node).fsm forward ToClient(DisconnectMsg(t.addr, abort))
        case Terminate(node, exitValue) ⇒
          barrier ! BarrierCoordinator.RemoveClient(node)
          nodes(node).fsm forward ToClient(TerminateMsg(exitValue))
        case Remove(node) ⇒
          barrier ! BarrierCoordinator.RemoveClient(node)
      }
    case GetNodes    ⇒ sender ! nodes.keys
    case GetSockAddr ⇒ sender ! connection.getLocalAddress
  }
}

/**
 * INTERNAL API.
 */
private[akka] object BarrierCoordinator {
  sealed trait State
  case object Idle extends State
  case object Waiting extends State

  case class RemoveClient(name: RoleName)

  case class Data(clients: Set[Controller.NodeInfo], barrier: String, arrived: List[ActorRef], deadline: Deadline)

  trait Printer { this: Product with Throwable with NoStackTrace ⇒
    override def toString = productPrefix + productIterator.mkString("(", ", ", ")")
  }

  case class BarrierTimeout(data: Data)
    extends RuntimeException("timeout while waiting for barrier '" + data.barrier + "'") with NoStackTrace with Printer
  case class FailedBarrier(data: Data)
    extends RuntimeException("failing barrier '" + data.barrier + "'") with NoStackTrace with Printer
  case class DuplicateNode(data: Data, node: Controller.NodeInfo)
    extends RuntimeException(node.toString) with NoStackTrace with Printer
  case class WrongBarrier(barrier: String, client: ActorRef, data: Data)
    extends RuntimeException(data.clients.find(_.fsm == client).map(_.name.toString).getOrElse(client.toString) +
      " tried to enter '" + barrier + "' while we were waiting for '" + data.barrier + "'") with NoStackTrace with Printer
  case class BarrierEmpty(data: Data, msg: String) extends RuntimeException(msg) with NoStackTrace with Printer
  case class ClientLost(data: Data, client: RoleName)
    extends RuntimeException("unannounced disconnect of " + client) with NoStackTrace with Printer
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
 *
 * INTERNAL API.
 */
private[akka] class BarrierCoordinator extends Actor with LoggingFSM[BarrierCoordinator.State, BarrierCoordinator.Data] {
  import BarrierCoordinator._
  import akka.actor.FSM._
  import Controller._
  import akka.util.{ Timeout ⇒ auTimeout }

  // this shall be set to true if all subsequent barriers shall fail
  var failed = false

  override def preRestart(reason: Throwable, message: Option[Any]) {}
  override def postRestart(reason: Throwable) { failed = true }

  // TODO what happens with the other waiting players in case of a test failure?

  startWith(Idle, Data(Set(), "", Nil, null))

  whenUnhandled {
    case Event(n: NodeInfo, d @ Data(clients, _, _, _)) ⇒
      if (clients.find(_.name == n.name).isDefined) throw new DuplicateNode(d, n)
      stay using d.copy(clients = clients + n)
    case Event(ClientDisconnected(name), d @ Data(clients, _, arrived, _)) ⇒
      if (arrived.isEmpty)
        stay using d.copy(clients = clients.filterNot(_.name == name))
      else {
        (clients find (_.name == name)) match {
          case None    ⇒ stay
          case Some(c) ⇒ throw ClientLost(d.copy(clients = clients - c, arrived = arrived filterNot (_ == c.fsm)), name)
        }
      }
  }

  when(Idle) {
    case Event(EnterBarrier(name, timeout), d @ Data(clients, _, _, _)) ⇒
      if (failed)
        stay replying ToClient(BarrierResult(name, false))
      else if (clients.map(_.fsm) == Set(sender))
        stay replying ToClient(BarrierResult(name, true))
      else if (clients.find(_.fsm == sender).isEmpty)
        stay replying ToClient(BarrierResult(name, false))
      else {
        goto(Waiting) using d.copy(barrier = name, arrived = sender :: Nil,
          deadline = getDeadline(timeout))
      }
    case Event(RemoveClient(name), d @ Data(clients, _, _, _)) ⇒
      if (clients.isEmpty) throw BarrierEmpty(d, "cannot remove " + name + ": no client to remove")
      stay using d.copy(clients = clients filterNot (_.name == name))
  }

  onTransition {
    case Idle -> Waiting ⇒ setTimer("Timeout", StateTimeout, nextStateData.deadline.timeLeft, false)
    case Waiting -> Idle ⇒ cancelTimer("Timeout")
  }

  when(Waiting) {
    case Event(EnterBarrier(name, timeout), d @ Data(clients, barrier, arrived, deadline)) ⇒
      if (name != barrier) throw WrongBarrier(name, sender, d)
      val together = if (clients.exists(_.fsm == sender)) sender :: arrived else arrived
      val enterDeadline = getDeadline(timeout)
      // we only allow the deadlines to get shorter
      if (enterDeadline.timeLeft < deadline.timeLeft) {
        setTimer("Timeout", StateTimeout, enterDeadline.timeLeft, false)
        handleBarrier(d.copy(arrived = together, deadline = enterDeadline))
      } else
        handleBarrier(d.copy(arrived = together))
    case Event(RemoveClient(name), d @ Data(clients, barrier, arrived, _)) ⇒
      clients find (_.name == name) match {
        case None ⇒ stay
        case Some(client) ⇒
          handleBarrier(d.copy(clients = clients - client, arrived = arrived filterNot (_ == client.fsm)))
      }
    case Event(FailBarrier(name), d @ Data(_, barrier, _, _)) ⇒
      if (name != barrier) throw WrongBarrier(name, sender, d)
      throw FailedBarrier(d)
    case Event(StateTimeout, d) ⇒
      throw BarrierTimeout(d)
  }

  initialize

  def handleBarrier(data: Data): State = {
    log.debug("handleBarrier({})", data)
    if (data.arrived.isEmpty) {
      goto(Idle) using data.copy(barrier = "")
    } else if ((data.clients.map(_.fsm) -- data.arrived).isEmpty) {
      data.arrived foreach (_ ! ToClient(BarrierResult(data.barrier, true)))
      goto(Idle) using data.copy(barrier = "", arrived = Nil)
    } else {
      stay using data
    }
  }

  def getDeadline(timeout: Option[FiniteDuration]): Deadline = {
    Deadline.now + timeout.getOrElse(TestConductor().Settings.BarrierTimeout.duration)
  }

}


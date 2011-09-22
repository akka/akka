/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.actor._
import Actor._
import akka.event.EventHandler
import akka.dispatch.{ Dispatchers, Future, PinnedDispatcher }
import akka.config.{ Config, Supervision }
import Supervision._
import Status._
import Config._
import akka.util._
import duration._
import Helpers._
import DeploymentConfig._
import akka.serialization.{ Serialization, Serializer, ActorSerialization, Compression }
import ActorSerialization._
import Compression.LZF
import RemoteProtocol._
import RemoteDaemonMessageType._

import java.net.InetSocketAddress

import com.eaio.uuid.UUID

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Remote extends RemoteService {
  val shouldCompressData = config.getBool("akka.remote.use-compression", false)
  val remoteDaemonAckTimeout = Duration(config.getInt("akka.remote.remote-daemon-ack-timeout", 30), TIME_UNIT).toMillis.toInt

  val hostname = Config.hostname
  val port = Config.remoteServerPort

  val remoteDaemonServiceName = "akka-remote-daemon".intern

  // FIXME configure computeGridDispatcher to what?
  val computeGridDispatcher = Dispatchers.newDispatcher("akka:compute-grid").build

  private[remote] lazy val remoteDaemon = new LocalActorRef(
    Props(new RemoteDaemon).copy(dispatcher = new PinnedDispatcher()),
    Remote.remoteDaemonServiceName,
    systemService = true)

  private[remote] lazy val remoteDaemonSupervisor = Supervisor(
    SupervisorConfig(
      OneForOnePermanentStrategy(List(classOf[Exception]), Int.MaxValue, Int.MaxValue), // is infinite restart what we want?
      Supervise(
        remoteDaemon,
        Permanent)
        :: Nil))

  private[remote] lazy val remoteClientLifeCycleHandler = actorOf(Props(new Actor {
    def receive = {
      case RemoteClientError(cause, client, address) ⇒ client.shutdownClientModule()
      case RemoteClientDisconnected(client, address) ⇒ client.shutdownClientModule()
      case _                                         ⇒ //ignore other
    }
  }), "akka.cluster.RemoteClientLifeCycleListener")

  lazy val server: RemoteSupport = {
    val remote = new akka.remote.netty.NettyRemoteSupport
    remote.start(hostname, port)
    remote.register(Remote.remoteDaemonServiceName, remoteDaemon)
    remote.addListener(NetworkEventStream.channel)
    remote.addListener(remoteClientLifeCycleHandler)
    Actor.provider.register(ActorRefProvider.RemoteProvider, new RemoteActorRefProvider)
    remote
  }

  lazy val address = server.address

  def start() {
    val triggerLazyServerVal = address.toString
    EventHandler.info(this, "Starting remote server on [%s]".format(triggerLazyServerVal))
  }

  def uuidProtocolToUuid(uuid: UuidProtocol): UUID = new UUID(uuid.getHigh, uuid.getLow)

  def uuidToUuidProtocol(uuid: UUID): UuidProtocol =
    UuidProtocol.newBuilder
      .setHigh(uuid.getTime)
      .setLow(uuid.getClockSeqAndNode)
      .build
}

/**
 * Internal "daemon" actor for cluster internal communication.
 *
 * It acts as the brain of the cluster that responds to cluster events (messages) and undertakes action.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteDaemon extends Actor {

  import Remote._

  override def preRestart(reason: Throwable, msg: Option[Any]) {
    EventHandler.debug(this, "RemoteDaemon failed due to [%s] restarting...".format(reason))
  }

  def receive: Receive = {
    case message: RemoteDaemonMessageProtocol ⇒
      EventHandler.debug(this,
        "Received command [\n%s] to RemoteDaemon on [%s]".format(message, address))

      message.getMessageType match {
        case USE                    ⇒ handleUse(message)
        case RELEASE                ⇒ handleRelease(message)
        // case STOP                   ⇒ cluster.shutdown()
        // case DISCONNECT             ⇒ cluster.disconnect()
        // case RECONNECT              ⇒ cluster.reconnect()
        // case RESIGN                 ⇒ cluster.resign()
        // case FAIL_OVER_CONNECTIONS  ⇒ handleFailover(message)
        case FUNCTION_FUN0_UNIT     ⇒ handle_fun0_unit(message)
        case FUNCTION_FUN0_ANY      ⇒ handle_fun0_any(message)
        case FUNCTION_FUN1_ARG_UNIT ⇒ handle_fun1_arg_unit(message)
        case FUNCTION_FUN1_ARG_ANY  ⇒ handle_fun1_arg_any(message)
        //TODO: should we not deal with unrecognized message types?
      }

    case unknown ⇒ EventHandler.warning(this, "Unknown message [%s]".format(unknown))
  }

  def handleUse(message: RemoteProtocol.RemoteDaemonMessageProtocol) {
    try {
      if (message.hasActorAddress) {

        val actorFactoryBytes =
          if (shouldCompressData) LZF.uncompress(message.getPayload.toByteArray)
          else message.getPayload.toByteArray

        val actorFactory =
          Serialization.deserialize(actorFactoryBytes, classOf[() ⇒ Actor], None) match {
            case Left(error)     ⇒ throw error
            case Right(instance) ⇒ instance.asInstanceOf[() ⇒ Actor]
          }

        val actorAddress = message.getActorAddress
        val newActorRef = actorOf(Props(creator = actorFactory), actorAddress)

        Remote.server.register(actorAddress, newActorRef)

      } else {
        EventHandler.error(this, "Actor 'address' is not defined, ignoring remote daemon command [%s]".format(message))
      }

      reply(Success(address.toString))
    } catch {
      case error: Throwable ⇒
        reply(Failure(error))
        throw error
    }
  }

  def handleRelease(message: RemoteProtocol.RemoteDaemonMessageProtocol) {
    // FIXME implement handleRelease without Cluster

    // if (message.hasActorUuid) {
    //   cluster.actorAddressForUuid(uuidProtocolToUuid(message.getActorUuid)) foreach { address ⇒
    //     cluster.release(address)
    //   }
    // } else if (message.hasActorAddress) {
    //   cluster release message.getActorAddress
    // } else {
    //   EventHandler.warning(this,
    //     "None of 'uuid' or 'actorAddress'' is specified, ignoring remote cluster daemon command [%s]".format(message))
    // }
  }

  def handle_fun0_unit(message: RemoteProtocol.RemoteDaemonMessageProtocol) {
    new LocalActorRef(
      Props(
        self ⇒ {
          case f: Function0[_] ⇒ try { f() } finally { self.stop() }
        }).copy(dispatcher = computeGridDispatcher), newUuid.toString, systemService = true) ! payloadFor(message, classOf[Function0[Unit]])
  }

  def handle_fun0_any(message: RemoteProtocol.RemoteDaemonMessageProtocol) {
    new LocalActorRef(
      Props(
        self ⇒ {
          case f: Function0[_] ⇒ try { reply(f()) } finally { self.stop() }
        }).copy(dispatcher = computeGridDispatcher), newUuid.toString, systemService = true) forward payloadFor(message, classOf[Function0[Any]])
  }

  def handle_fun1_arg_unit(message: RemoteProtocol.RemoteDaemonMessageProtocol) {
    new LocalActorRef(
      Props(
        self ⇒ {
          case (fun: Function[_, _], param: Any) ⇒ try { fun.asInstanceOf[Any ⇒ Unit].apply(param) } finally { self.stop() }
        }).copy(dispatcher = computeGridDispatcher), newUuid.toString, systemService = true) ! payloadFor(message, classOf[Tuple2[Function1[Any, Unit], Any]])
  }

  def handle_fun1_arg_any(message: RemoteProtocol.RemoteDaemonMessageProtocol) {
    new LocalActorRef(
      Props(
        self ⇒ {
          case (fun: Function[_, _], param: Any) ⇒ try { reply(fun.asInstanceOf[Any ⇒ Any](param)) } finally { self.stop() }
        }).copy(dispatcher = computeGridDispatcher), newUuid.toString, systemService = true) forward payloadFor(message, classOf[Tuple2[Function1[Any, Any], Any]])
  }

  def handleFailover(message: RemoteProtocol.RemoteDaemonMessageProtocol) {
    // val (from, to) = payloadFor(message, classOf[(InetSocketremoteDaemonServiceName, InetSocketremoteDaemonServiceName)])
    // cluster.failOverClusterActorRefConnections(from, to)
  }

  private def payloadFor[T](message: RemoteDaemonMessageProtocol, clazz: Class[T]): T = {
    Serialization.deserialize(message.getPayload.toByteArray, clazz, None) match {
      case Left(error)     ⇒ throw error
      case Right(instance) ⇒ instance.asInstanceOf[T]
    }
  }
}

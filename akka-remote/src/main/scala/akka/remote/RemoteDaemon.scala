/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.AkkaApplication
import akka.actor._
import akka.event.EventHandler
import akka.dispatch.{ Dispatchers, Future, PinnedDispatcher }
import akka.actor.Status._
import akka.util._
import akka.util.duration._
import akka.util.Helpers._
import akka.actor.DeploymentConfig._
import akka.serialization.{ Serialization, Serializer, ActorSerialization, Compression }
import Compression.LZF
import RemoteProtocol._
import RemoteDaemonMessageType._

import java.net.InetSocketAddress

import com.eaio.uuid.UUID

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class Remote(val app: AkkaApplication) extends RemoteService {

  import app.config
  import app.AkkaConfig.DefaultTimeUnit

  val shouldCompressData = config.getBool("akka.remote.use-compression", false)
  val remoteDaemonAckTimeout = Duration(config.getInt("akka.remote.remote-daemon-ack-timeout", 30), DefaultTimeUnit).toMillis.toInt

  val hostname = app.hostname
  val port = app.port

  val remoteDaemonServiceName = "akka-remote-daemon".intern

  // FIXME configure computeGridDispatcher to what?
  val computeGridDispatcher = app.dispatcherFactory.newDispatcher("akka:compute-grid").build

  private[remote] lazy val remoteDaemonSupervisor = app.actorOf(Props(
    OneForOneStrategy(List(classOf[Exception]), None, None))) // is infinite restart what we want?

  private[remote] lazy val remoteDaemon =
    new LocalActorRef(
      app,
      props = Props(new RemoteDaemon(this)).withDispatcher(app.dispatcherFactory.newPinnedDispatcher("Remote")).withSupervisor(remoteDaemonSupervisor),
      givenAddress = remoteDaemonServiceName,
      systemService = true)

  private[remote] lazy val remoteClientLifeCycleHandler = app.actorOf(Props(new Actor {
    def receive = {
      case RemoteClientError(cause, client, address) ⇒ client.shutdownClientModule()
      case RemoteClientDisconnected(client, address) ⇒ client.shutdownClientModule()
      case _                                         ⇒ //ignore other
    }
  }), "akka.cluster.RemoteClientLifeCycleListener")

  lazy val eventStream = new NetworkEventStream(app)

  lazy val server: RemoteSupport = {
    val remote = new akka.remote.netty.NettyRemoteSupport(app)
    remote.start(hostname, port)
    remote.register(remoteDaemonServiceName, remoteDaemon)
    remote.addListener(eventStream.channel)
    remote.addListener(remoteClientLifeCycleHandler)
    // TODO actually register this provider in app in remote mode
    //app.provider.register(ActorRefProvider.RemoteProvider, new RemoteActorRefProvider)
    remote
  }

  lazy val address = server.address

  def start() {
    val triggerLazyServerVal = address.toString
    app.eventHandler.info(this, "Starting remote server on [%s]".format(triggerLazyServerVal))
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
class RemoteDaemon(val remote: Remote) extends Actor {

  import remote._

  override def preRestart(reason: Throwable, msg: Option[Any]) {
    app.eventHandler.debug(this, "RemoteDaemon failed due to [%s] restarting...".format(reason))
  }

  def receive: Actor.Receive = {
    case message: RemoteDaemonMessageProtocol ⇒
      app.eventHandler.debug(this,
        "Received command [\n%s] to RemoteDaemon on [%s]".format(message, app.nodename))

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

    case unknown ⇒ app.eventHandler.warning(this, "Unknown message [%s]".format(unknown))
  }

  def handleUse(message: RemoteProtocol.RemoteDaemonMessageProtocol) {
    try {
      if (message.hasActorAddress) {

        val actorFactoryBytes =
          if (shouldCompressData) LZF.uncompress(message.getPayload.toByteArray)
          else message.getPayload.toByteArray

        val actorFactory =
          app.serialization.deserialize(actorFactoryBytes, classOf[() ⇒ Actor], None) match {
            case Left(error)     ⇒ throw error
            case Right(instance) ⇒ instance.asInstanceOf[() ⇒ Actor]
          }

        val actorAddress = message.getActorAddress
        val newActorRef = app.actorOf(Props(creator = actorFactory), actorAddress)

        remote.server.register(actorAddress, newActorRef)

      } else {
        app.eventHandler.error(this, "Actor 'address' is not defined, ignoring remote daemon command [%s]".format(message))
      }

      channel ! Success(address.toString)
    } catch {
      case error: Throwable ⇒
        channel ! Failure(error)
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
    new LocalActorRef(app,
      Props(
        context ⇒ {
          case f: Function0[_] ⇒ try { f() } finally { context.self.stop() }
        }).copy(dispatcher = computeGridDispatcher), Props.randomAddress, systemService = true) ! payloadFor(message, classOf[Function0[Unit]])
  }

  def handle_fun0_any(message: RemoteProtocol.RemoteDaemonMessageProtocol) {
    new LocalActorRef(app,
      Props(
        context ⇒ {
          case f: Function0[_] ⇒ try { channel ! f() } finally { context.self.stop() }
        }).copy(dispatcher = computeGridDispatcher), Props.randomAddress, systemService = true) forward payloadFor(message, classOf[Function0[Any]])
  }

  def handle_fun1_arg_unit(message: RemoteProtocol.RemoteDaemonMessageProtocol) {
    new LocalActorRef(app,
      Props(
        context ⇒ {
          case (fun: Function[_, _], param: Any) ⇒ try { fun.asInstanceOf[Any ⇒ Unit].apply(param) } finally { context.self.stop() }
        }).copy(dispatcher = computeGridDispatcher), Props.randomAddress, systemService = true) ! payloadFor(message, classOf[Tuple2[Function1[Any, Unit], Any]])
  }

  def handle_fun1_arg_any(message: RemoteProtocol.RemoteDaemonMessageProtocol) {
    new LocalActorRef(app,
      Props(
        context ⇒ {
          case (fun: Function[_, _], param: Any) ⇒ try { channel ! fun.asInstanceOf[Any ⇒ Any](param) } finally { context.self.stop() }
        }).copy(dispatcher = computeGridDispatcher), Props.randomAddress, systemService = true) forward payloadFor(message, classOf[Tuple2[Function1[Any, Any], Any]])
  }

  def handleFailover(message: RemoteProtocol.RemoteDaemonMessageProtocol) {
    // val (from, to) = payloadFor(message, classOf[(InetSocketremoteDaemonServiceName, InetSocketremoteDaemonServiceName)])
    // cluster.failOverClusterActorRefConnections(from, to)
  }

  private def payloadFor[T](message: RemoteDaemonMessageProtocol, clazz: Class[T]): T = {
    app.serialization.deserialize(message.getPayload.toByteArray, clazz, None) match {
      case Left(error)     ⇒ throw error
      case Right(instance) ⇒ instance.asInstanceOf[T]
    }
  }
}

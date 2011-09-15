/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

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
  val shouldCompressData = config.getBool("akka.cluster.use-compression", false)
  val remoteDaemonAckTimeout = Duration(config.getInt("akka.cluster.remote-daemon-ack-timeout", 30), TIME_UNIT).toMillis.toInt

  val hostname = Config.hostname
  val port = Config.remoteServerPort

  val remoteAddress = "akka-remote-daemon".intern

  // FIXME configure computeGridDispatcher to what?
  val computeGridDispatcher = Dispatchers.newDispatcher("akka:compute-grid").build

  private[cluster] lazy val remoteDaemon = new LocalActorRef(
    Props(new RemoteDaemon).copy(dispatcher = new PinnedDispatcher()),
    Remote.remoteAddress,
    systemService = true)

  private[cluster] lazy val remoteDaemonSupervisor = Supervisor(
    SupervisorConfig(
      OneForOneStrategy(List(classOf[Exception]), Int.MaxValue, Int.MaxValue), // is infinite restart what we want?
      Supervise(
        remoteDaemon,
        Permanent)
        :: Nil))

  private[cluster] lazy val remoteClientLifeCycleHandler = actorOf(Props(new Actor {
    def receive = {
      case RemoteClientError(cause, client, address) ⇒ client.shutdownClientModule()
      case RemoteClientDisconnected(client, address) ⇒ client.shutdownClientModule()
      case _                                         ⇒ //ignore other
    }
  }), "akka.cluster.RemoteClientLifeCycleListener")

  lazy val server: RemoteSupport = {
    val remote = new akka.cluster.netty.NettyRemoteSupport
    remote.start(hostname, port)
    remote.register(Remote.remoteAddress, remoteDaemon)
    remote.addListener(RemoteFailureDetector.channel)
    remote.addListener(remoteClientLifeCycleHandler)
    remote
  }

  lazy val address = server.address

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
        val props =
          Serialization.deserialize(propsBytes, classOf[Props], None) match {
            case Left(error)     ⇒ throw error
            case Right(instance) ⇒ instance.asInstanceOf[Props]
          }

        val actorAddress = message.getActorAddress
        val newActorRef = actorOf(props)

        Remote.server.register(actorAddress, newActorRef)

        // if (message.hasReplicateActorFromUuid) {

        // def deserializeMessages(entriesAsBytes: Vector[Array[Byte]]): Vector[AnyRef] = {
        //   import akka.cluster.RemoteProtocol._
        //   import akka.cluster.MessageSerializer

        //   entriesAsBytes map { bytes ⇒
        //     val messageBytes =
        //       if (shouldCompressData) LZF.uncompress(bytes)
        //       else bytes
        //     MessageSerializer.deserialize(MessageProtocol.parseFrom(messageBytes), None)
        //   }
        // }
        // def createActorRefToUseForReplay(snapshotAsBytes: Option[Array[Byte]], actorAddress: String, newActorRef: LocalActorRef): ActorRef = {
        //   snapshotAsBytes match {

        //     // we have a new actor ref - the snapshot
        //     case Some(bytes) ⇒
        //       // stop the new actor ref and use the snapshot instead
        //       //TODO: What if that actor already has been retrieved and is being used??
        //       //So do we have a race here?
        //       server.unregister(actorAddress)

        //       // deserialize the snapshot actor ref and register it as remote actor
        //       val uncompressedBytes =
        //         if (shouldCompressData) LZF.uncompress(bytes)
        //         else bytes

        //       val snapshotActorRef = fromBinary(uncompressedBytes, newActorRef.uuid)
        //       server.register(actorAddress, snapshotActorRef)

        //       // FIXME we should call 'stop()' here (to GC the actor), but can't since that will currently
        //       //shut down the TransactionLog for this UUID - since both this actor and the new snapshotActorRef
        //       //have the same UUID (which they should)
        //       //newActorRef.stop()

        //       snapshotActorRef

        //     // we have no snapshot - use the new actor ref
        //     case None ⇒
        //       newActorRef
        //   }
        // }

        //   // replication is used - fetch the messages and replay them
        //   val replicateFromUuid = uuidProtocolToUuid(message.getReplicateActorFromUuid)
        //   val deployment = Deployer.deploymentFor(actorAddress)
        //   val replicationScheme = DeploymentConfig.replicationSchemeFor(deployment).getOrElse(
        //     throw new IllegalStateException(
        //       "Actor [" + actorAddress + "] should have been configured as a replicated actor but could not find its ReplicationScheme"))
        //   val isWriteBehind = DeploymentConfig.isWriteBehindReplication(replicationScheme)

        //   try {
        //     // get the transaction log for the actor UUID
        //     val readonlyTxLog = TransactionLog.logFor(replicateFromUuid.toString, isWriteBehind, replicationScheme)

        //     // get the latest snapshot (Option[Array[Byte]]) and all the subsequent messages (Array[Byte])
        //     val (snapshotAsBytes, entriesAsBytes) = readonlyTxLog.latestSnapshotAndSubsequentEntries

        //     // deserialize and restore actor snapshot. This call will automatically recreate a transaction log.
        //     val actorRef = createActorRefToUseForReplay(snapshotAsBytes, actorAddress, newActorRef)

        //     // deserialize the messages
        //     val messages: Vector[AnyRef] = deserializeMessages(entriesAsBytes)

        //     EventHandler.info(this, "Replaying [%s] messages to actor [%s]".format(messages.size, actorAddress))

        //     // replay all messages
        //     messages foreach { message ⇒
        //       EventHandler.debug(this, "Replaying message [%s] to actor [%s]".format(message, actorAddress))

        //       // FIXME how to handle '?' messages?
        //       // We can *not* replay them with the correct semantics. Should we:
        //       // 1. Ignore/drop them and log warning?
        //       // 2. Throw exception when about to log them?
        //       // 3. Other?
        //       actorRef ! message
        //     }

        //   } catch {
        //     case e: Throwable ⇒
        //       EventHandler.error(e, this, e.toString)
        //       throw e
        //   }
        // }

      } else {
        EventHandler.error(this, "Actor 'address' is not defined, ignoring remote daemon command [%s]".format(message))
      }

      self.reply(Success(address.toString))
    } catch {
      case error: Throwable ⇒
        self.reply(Failure(error))
        throw error
    }
  }

  def handleRelease(message: RemoteProtocol.RemoteDaemonMessageProtocol) {
    if (message.hasActorUuid) {
      cluster.actorAddressForUuid(uuidProtocolToUuid(message.getActorUuid)) foreach { address ⇒
        cluster.release(address)
      }
    } else if (message.hasActorAddress) {
      cluster release message.getActorAddress
    } else {
      EventHandler.warning(this,
        "None of 'uuid' or 'actorAddress'' is specified, ignoring remote cluster daemon command [%s]".format(message))
    }
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
          case f: Function0[_] ⇒ try { self.reply(f()) } finally { self.stop() }
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
          case (fun: Function[_, _], param: Any) ⇒ try { self.reply(fun.asInstanceOf[Any ⇒ Any](param)) } finally { self.stop() }
        }).copy(dispatcher = computeGridDispatcher), newUuid.toString, systemService = true) forward payloadFor(message, classOf[Tuple2[Function1[Any, Any], Any]])
  }

  def handleFailover(message: RemoteProtocol.RemoteDaemonMessageProtocol) {
    // val (from, to) = payloadFor(message, classOf[(InetSocketremoteAddress, InetSocketremoteAddress)])
    // cluster.failOverClusterActorRefConnections(from, to)
  }

  private def payloadFor[T](message: RemoteDaemonMessageProtocol, clazz: Class[T]): T = {
    Serialization.deserialize(message.getPayload.toByteArray, clazz, None) match {
      case Left(error)     ⇒ throw error
      case Right(instance) ⇒ instance.asInstanceOf[T]
    }
  }
}

/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.actor.ActorSystem
import akka.actor._
import akka.event.Logging
import akka.actor.Status._
import akka.util._
import akka.util.duration._
import akka.util.Helpers._
import akka.actor.DeploymentConfig._
import akka.serialization.Compression.LZF
import akka.remote.RemoteProtocol._
import akka.remote.RemoteProtocol.RemoteSystemDaemonMessageType._
import java.net.InetSocketAddress
import com.eaio.uuid.UUID
import akka.serialization.{ JavaSerializer, Serialization, Serializer, Compression }
import akka.dispatch.{ Terminate, Dispatchers, Future, PinnedDispatcher }
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.serialization.SerializationExtension
import akka.dispatch.SystemMessage
import akka.event.LoggingAdapter

/**
 * Remote module - contains remote client and server config, remote server instance, remote daemon, remote dispatchers etc.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class Remote(val system: ActorSystemImpl, val nodename: String, val remoteSettings: RemoteSettings) {

  val log = Logging(system, "Remote")

  import system._
  import settings._

  private[remote] val serialization = SerializationExtension(system)
  private[remote] val remoteAddress = {
    RemoteAddress(system.name, remoteSettings.serverSettings.Hostname, remoteSettings.serverSettings.Port)
  }

  val failureDetector = new AccrualFailureDetector(remoteSettings.FailureDetectorThreshold, remoteSettings.FailureDetectorMaxSampleSize)

  //  val gossiper = new Gossiper(this)

  val remoteDaemonServiceName = "akka-system-remote-daemon".intern

  val computeGridDispatcher = dispatcherFactory.fromConfig("akka.remote.compute-grid-dispatcher")

  // FIXME it is probably better to create another supervisor for handling the children created by handle_*, ticket #1408
  val remoteDaemonSupervisor =
    system.provider.actorOf(system,
      Props(OneForOneStrategy(List(classOf[Exception]), None, None)),
      system.provider.rootGuardian,
      "akka-system-remote-supervisor",
      systemService = true) // is infinite restart what we want?

  val remoteDaemon =
    system.provider.actorOf(system,
      Props(new RemoteSystemDaemon(this)).withDispatcher(dispatcherFactory.newPinnedDispatcher(remoteDaemonServiceName)),
      remoteDaemonSupervisor,
      remoteDaemonServiceName,
      systemService = true)

  val remoteClientLifeCycleHandler = system.actorOf(Props(new Actor {
    def receive = {
      case RemoteClientError(cause, remote, address) ⇒ remote.shutdownClientConnection(address)
      case RemoteClientDisconnected(remote, address) ⇒ remote.shutdownClientConnection(address)
      case _                                         ⇒ //ignore other
    }
  }), "akka.remote.RemoteClientLifeCycleListener")

  val eventStream = new NetworkEventStream(system)

  val server: RemoteSupport = {
    val arguments = Seq(
      classOf[ActorSystem] -> system,
      classOf[Remote] -> this)
    val types: Array[Class[_]] = arguments map (_._1) toArray
    val values: Array[AnyRef] = arguments map (_._2) toArray

    ReflectiveAccess.createInstance[RemoteSupport](remoteSettings.RemoteTransport, types, values) match {
      case Left(problem) ⇒
        log.error(problem, "Could not load remote transport layer")
        throw problem
      case Right(remote) ⇒
        remote.start(None) //TODO Any application loader here?

        system.eventStream.subscribe(eventStream.sender, classOf[RemoteLifeCycleEvent])
        system.eventStream.subscribe(remoteClientLifeCycleHandler, classOf[RemoteLifeCycleEvent])

        remote
    }
  }

  log.info("Starting remote server on [{}] and starting remoteDaemon {}", remoteAddress, remoteDaemon)
}

/**
 * Internal system "daemon" actor for remote internal communication.
 *
 * It acts as the brain of the remote that responds to system remote events (messages) and undertakes action.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteSystemDaemon(remote: Remote) extends Actor {

  import remote._
  import remote.{ system ⇒ systemImpl }

  override def preRestart(reason: Throwable, msg: Option[Any]) {
    log.debug("RemoteSystemDaemon failed due to [{}] - restarting...", reason)
  }

  def receive: Actor.Receive = {
    case message: RemoteSystemDaemonMessageProtocol ⇒
      log.debug("Received command [\n{}] to RemoteSystemDaemon on [{}]", message.getMessageType, nodename)

      message.getMessageType match {
        case USE                    ⇒ handleUse(message)
        case RELEASE                ⇒ handleRelease(message)
        // case STOP                   ⇒ cluster.shutdown()
        // case DISCONNECT             ⇒ cluster.disconnect()
        // case RECONNECT              ⇒ cluster.reconnect()
        // case RESIGN                 ⇒ cluster.resign()
        // case FAIL_OVER_CONNECTIONS  ⇒ handleFailover(message)
        case GOSSIP                 ⇒ handleGossip(message)
        case FUNCTION_FUN0_UNIT     ⇒ handle_fun0_unit(message)
        case FUNCTION_FUN0_ANY      ⇒ handle_fun0_any(message)
        case FUNCTION_FUN1_ARG_UNIT ⇒ handle_fun1_arg_unit(message)
        case FUNCTION_FUN1_ARG_ANY  ⇒ handle_fun1_arg_any(message)
        //TODO: should we not deal with unrecognized message types?
      }

    case unknown ⇒ log.warning("Unknown message to RemoteSystemDaemon [{}]", unknown)
  }

  def handleUse(message: RemoteSystemDaemonMessageProtocol) {
    try {
      if (message.hasActorPath) {

        val actorFactoryBytes =
          if (remoteSettings.ShouldCompressData) LZF.uncompress(message.getPayload.toByteArray) else message.getPayload.toByteArray

        val actorFactory =
          serialization.deserialize(actorFactoryBytes, classOf[() ⇒ Actor], None) match {
            case Left(error)     ⇒ throw error
            case Right(instance) ⇒ instance.asInstanceOf[() ⇒ Actor]
          }

        message.getActorPath match {
          case RemoteActorPath(`remoteAddress`, elems) if elems.size > 0 ⇒
            val name = elems.last
            systemImpl.provider.actorFor(systemImpl.lookupRoot, elems.dropRight(1)) match {
              case x if x eq system.deadLetters ⇒
                log.error("Parent actor does not exist, ignoring remote system daemon command [{}]", message)
              case parent ⇒
                systemImpl.provider.actorOf(systemImpl, Props(creator = actorFactory), parent, name)
            }
          case _ ⇒
            log.error("remote path does not match path from message [{}]", message)
        }

      } else {
        log.error("Actor 'address' for actor to instantiate is not defined, ignoring remote system daemon command [{}]", message)
      }

      sender ! Success(remoteAddress)
    } catch {
      case exc: Exception ⇒
        sender ! Failure(exc)
        throw exc
    }

  }

  // FIXME implement handleRelease
  def handleRelease(message: RemoteSystemDaemonMessageProtocol) {
  }

  def handleGossip(message: RemoteSystemDaemonMessageProtocol) {
    // try {
    //   val gossip = serialization.deserialize(message.getPayload.toByteArray, classOf[Gossip], None) match {
    //     case Left(error)     ⇒ throw error
    //     case Right(instance) ⇒ instance.asInstanceOf[Gossip]
    //   }

    //   gossiper tell gossip

    //   sender ! Success(address.toString)
    // } catch {
    //   case error: Throwable ⇒
    //     sender ! Failure(error)
    //     throw error
    // }
  }

  /*
   * generate name for temporary actor refs
   */
  private val tempNumber = new AtomicLong
  def tempName = "$_" + Helpers.base64(tempNumber.getAndIncrement())
  def tempPath = remoteDaemon.path / tempName

  // FIXME: handle real remote supervision, ticket #1408
  def handle_fun0_unit(message: RemoteSystemDaemonMessageProtocol) {
    new LocalActorRef(systemImpl,
      Props(
        context ⇒ {
          case f: Function0[_] ⇒ try { f() } finally { context.self.stop() }
        }).copy(dispatcher = computeGridDispatcher), remoteDaemon, tempPath, systemService = true) ! payloadFor(message, classOf[Function0[Unit]])
  }

  // FIXME: handle real remote supervision, ticket #1408
  def handle_fun0_any(message: RemoteSystemDaemonMessageProtocol) {
    new LocalActorRef(systemImpl,
      Props(
        context ⇒ {
          case f: Function0[_] ⇒ try { sender ! f() } finally { context.self.stop() }
        }).copy(dispatcher = computeGridDispatcher), remoteDaemon, tempPath, systemService = true) forward payloadFor(message, classOf[Function0[Any]])
  }

  // FIXME: handle real remote supervision, ticket #1408
  def handle_fun1_arg_unit(message: RemoteSystemDaemonMessageProtocol) {
    new LocalActorRef(systemImpl,
      Props(
        context ⇒ {
          case (fun: Function[_, _], param: Any) ⇒ try { fun.asInstanceOf[Any ⇒ Unit].apply(param) } finally { context.self.stop() }
        }).copy(dispatcher = computeGridDispatcher), remoteDaemon, tempPath, systemService = true) ! payloadFor(message, classOf[Tuple2[Function1[Any, Unit], Any]])
  }

  // FIXME: handle real remote supervision, ticket #1408
  def handle_fun1_arg_any(message: RemoteSystemDaemonMessageProtocol) {
    new LocalActorRef(systemImpl,
      Props(
        context ⇒ {
          case (fun: Function[_, _], param: Any) ⇒ try { sender ! fun.asInstanceOf[Any ⇒ Any](param) } finally { context.self.stop() }
        }).copy(dispatcher = computeGridDispatcher), remoteDaemon, tempPath, systemService = true) forward payloadFor(message, classOf[Tuple2[Function1[Any, Any], Any]])
  }

  def handleFailover(message: RemoteSystemDaemonMessageProtocol) {
    // val (from, to) = payloadFor(message, classOf[(InetSocketremoteDaemonServiceName, InetSocketremoteDaemonServiceName)])
    // cluster.failOverClusterActorRefConnections(from, to)
  }

  private def payloadFor[T](message: RemoteSystemDaemonMessageProtocol, clazz: Class[T]): T = {
    serialization.deserialize(message.getPayload.toByteArray, clazz, None) match {
      case Left(error)     ⇒ throw error
      case Right(instance) ⇒ instance.asInstanceOf[T]
    }
  }
}

class RemoteMessage(input: RemoteMessageProtocol, remote: RemoteSupport, classLoader: Option[ClassLoader] = None) {

  def provider = remote.system.asInstanceOf[ActorSystemImpl].provider

  lazy val sender: ActorRef =
    if (input.hasSender) provider.actorFor(provider.rootGuardian, input.getSender.getPath)
    else remote.system.deadLetters

  lazy val recipient: InternalActorRef = provider.actorFor(provider.rootGuardian, input.getRecipient.getPath)

  lazy val payload: AnyRef = MessageSerializer.deserialize(remote.system, input.getMessage, classLoader)

  override def toString = "RemoteMessage: " + payload + " to " + recipient + " from " + sender
}

trait RemoteMarshallingOps {

  def log: LoggingAdapter

  def system: ActorSystem

  protected def useUntrustedMode: Boolean

  def createMessageSendEnvelope(rmp: RemoteMessageProtocol): AkkaRemoteProtocol = {
    val arp = AkkaRemoteProtocol.newBuilder
    arp.setMessage(rmp)
    arp.build
  }

  def createControlEnvelope(rcp: RemoteControlProtocol): AkkaRemoteProtocol = {
    val arp = AkkaRemoteProtocol.newBuilder
    arp.setInstruction(rcp)
    arp.build
  }

  /**
   * Serializes the ActorRef instance into a Protocol Buffers (protobuf) Message.
   */
  def toRemoteActorRefProtocol(actor: ActorRef): ActorRefProtocol = {
    ActorRefProtocol.newBuilder.setPath(actor.path.toString).build
  }

  def createRemoteMessageProtocolBuilder(
    recipient: ActorRef,
    message: Any,
    senderOption: Option[ActorRef]): RemoteMessageProtocol.Builder = {

    val messageBuilder = RemoteMessageProtocol.newBuilder.setRecipient(toRemoteActorRefProtocol(recipient))
    messageBuilder.setMessage(MessageSerializer.serialize(system, message.asInstanceOf[AnyRef]))

    if (senderOption.isDefined) messageBuilder.setSender(toRemoteActorRefProtocol(senderOption.get))

    messageBuilder
  }

  def receiveMessage(remoteMessage: RemoteMessage) {
    log.debug("received message {}", remoteMessage)

    remoteMessage.recipient match {
      case l @ (_: LocalActorRef | _: MinimalActorRef) ⇒
        remoteMessage.payload match {
          case msg: SystemMessage ⇒
            if (useUntrustedMode)
              throw new SecurityException("RemoteModule server is operating is untrusted mode, can not send system message")
            else l.sendSystemMessage(msg)
          case _: AutoReceivedMessage if (useUntrustedMode) ⇒
            throw new SecurityException("RemoteModule server is operating is untrusted mode, can not pass on a AutoReceivedMessage to the remote actor")
          case m ⇒ l.!(m)(remoteMessage.sender)
        }
      case r ⇒ log.error("dropping message {} for non-local recipient {}", remoteMessage.payload, r)
    }
  }
}

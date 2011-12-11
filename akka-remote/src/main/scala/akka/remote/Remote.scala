/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.actor._
import akka.event._
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
import akka.serialization.{ JavaSerializer, Serialization, Serializer, Compression, SerializationExtension }
import akka.dispatch.{ Terminate, Dispatchers, Future, PinnedDispatcher, MessageDispatcher }
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.dispatch.SystemMessage
import scala.annotation.tailrec

/**
 * Remote module - contains remote client and server config, remote server instance, remote daemon, remote dispatchers etc.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class Remote(val settings: ActorSystem.Settings, val remoteSettings: RemoteSettings) {

  import settings._

  // TODO make this really pluggable
  val transports: TransportsMap = Map("akka" -> ((h, p) ⇒ Right(RemoteNettyAddress(h, p))))
  val remoteAddress: RemoteSystemAddress[ParsedTransportAddress] = {
    val unparsedAddress = remoteSettings.serverSettings.URI match {
      case RemoteAddressExtractor(a) ⇒ a
      case x                         ⇒ throw new IllegalArgumentException("cannot parse URI " + x)
    }
    val parsed = unparsedAddress.parse(transports) match {
      case Left(x)  ⇒ throw new IllegalArgumentException(x.transport.error)
      case Right(x) ⇒ x
    }
    parsed.copy(system = settings.name)
  }

  val failureDetector = new AccrualFailureDetector(remoteSettings.FailureDetectorThreshold, remoteSettings.FailureDetectorMaxSampleSize)

  @volatile
  private var _serialization: Serialization = _
  def serialization = _serialization

  @volatile
  private var _computeGridDispatcher: MessageDispatcher = _
  def computeGridDispatcher = _computeGridDispatcher

  @volatile
  private var _remoteDaemon: InternalActorRef = _
  def remoteDaemon = _remoteDaemon

  @volatile
  private var _eventStream: NetworkEventStream = _
  def eventStream = _eventStream

  @volatile
  private var _server: RemoteSupport[ParsedTransportAddress] = _
  def server = _server

  def init(system: ActorSystemImpl) = {

    val log = Logging(system, "Remote")

    _serialization = SerializationExtension(system)
    _computeGridDispatcher = system.dispatcherFactory.fromConfig("akka.remote.compute-grid-dispatcher")
    _remoteDaemon = new RemoteSystemDaemon(system, this, system.provider.rootPath / "remote", system.provider.rootGuardian, log)
    _eventStream = new NetworkEventStream(system)
    _server = {
      val arguments = Seq(
        classOf[ActorSystemImpl] -> system,
        classOf[Remote] -> this,
        classOf[RemoteSystemAddress[_ <: ParsedTransportAddress]] -> remoteAddress)
      val types: Array[Class[_]] = arguments map (_._1) toArray
      val values: Array[AnyRef] = arguments map (_._2) toArray

      ReflectiveAccess.createInstance[RemoteSupport[ParsedTransportAddress]](remoteSettings.RemoteTransport, types, values) match {
        case Left(problem) ⇒

          log.error(problem, "Could not load remote transport layer")
          throw problem

        case Right(remote) ⇒

          remote.start(None) //TODO Any application loader here?

          val remoteClientLifeCycleHandler = system.systemActorOf(Props(new Actor {
            def receive = {
              case RemoteClientError(cause, remote, address) ⇒ remote.shutdownClientConnection(address)
              case RemoteClientDisconnected(remote, address) ⇒ remote.shutdownClientConnection(address)
              case _                                         ⇒ //ignore other
            }
          }), "RemoteClientLifeCycleListener")

          system.eventStream.subscribe(eventStream.sender, classOf[RemoteLifeCycleEvent])
          system.eventStream.subscribe(remoteClientLifeCycleHandler, classOf[RemoteLifeCycleEvent])

          remote
      }
    }

    log.info("Starting remote server on [{}]", remoteAddress)
  }
}

/**
 * Internal system "daemon" actor for remote internal communication.
 *
 * It acts as the brain of the remote that responds to system remote events (messages) and undertakes action.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteSystemDaemon(system: ActorSystemImpl, remote: Remote, _path: ActorPath, _parent: InternalActorRef, _log: LoggingAdapter)
  extends VirtualPathContainer(_path, _parent, _log) {

  /**
   * Find the longest matching path which we know about and return that ref
   * (or ask that ref to continue searching if elements are left).
   */
  override def getChild(names: Iterator[String]): InternalActorRef = {

    @tailrec
    def rec(s: String, n: Int): (InternalActorRef, Int) = {
      getChild(s) match {
        case null ⇒
          val last = s.lastIndexOf('/')
          if (last == -1) (Nobody, n)
          else rec(s.substring(0, last), n + 1)
        case ref ⇒ (ref, n)
      }
    }

    val full = Vector() ++ names
    rec(full.mkString("/"), 0) match {
      case (Nobody, _)        ⇒ Nobody
      case (ref, n) if n == 0 ⇒ ref
      case (ref, n)           ⇒ ref.getChild(full.takeRight(n).iterator)
    }
  }

  override def !(msg: Any)(implicit sender: ActorRef = null): Unit = msg match {
    case message: RemoteSystemDaemonMessageProtocol ⇒
      log.debug("Received command [\n{}] to RemoteSystemDaemon on [{}]", message.getMessageType, remote.remoteSettings.NodeName)

      message.getMessageType match {
        case USE     ⇒ handleUse(message)
        case RELEASE ⇒ handleRelease(message)
        // case STOP                   ⇒ cluster.shutdown()
        // case DISCONNECT             ⇒ cluster.disconnect()
        // case RECONNECT              ⇒ cluster.reconnect()
        // case RESIGN                 ⇒ cluster.resign()
        // case FAIL_OVER_CONNECTIONS  ⇒ handleFailover(message)
        case GOSSIP  ⇒ handleGossip(message)
        //        case FUNCTION_FUN0_UNIT     ⇒ handle_fun0_unit(message)
        //        case FUNCTION_FUN0_ANY      ⇒ handle_fun0_any(message, sender)
        //        case FUNCTION_FUN1_ARG_UNIT ⇒ handle_fun1_arg_unit(message)
        //        case FUNCTION_FUN1_ARG_ANY  ⇒ handle_fun1_arg_any(message, sender)
        case unknown ⇒ log.warning("Unknown message type {} received by {}", unknown, this)
      }

    case Terminated(child) ⇒ removeChild(child.path.elements.drop(1).mkString("/"))

    case unknown           ⇒ log.warning("Unknown message {} received by {}", unknown, this)
  }

  def handleUse(message: RemoteSystemDaemonMessageProtocol) {

    if (!message.hasActorPath || !message.hasSupervisor) log.error("Ignoring incomplete USE command [{}]", message)
    else {

      val actorFactoryBytes =
        if (remote.remoteSettings.ShouldCompressData) LZF.uncompress(message.getPayload.toByteArray)
        else message.getPayload.toByteArray

      val actorFactory =
        remote.serialization.deserialize(actorFactoryBytes, classOf[() ⇒ Actor], None) match {
          case Left(error)     ⇒ throw error
          case Right(instance) ⇒ instance.asInstanceOf[() ⇒ Actor]
        }

      import remote.remoteAddress
      implicit val t = remote.transports

      message.getActorPath match {
        case ParsedActorPath(`remoteAddress`, elems) if elems.nonEmpty && elems.head == "remote" ⇒
          // TODO RK canonicalize path so as not to duplicate it always #1446
          val subpath = elems.drop(1)
          val path = remote.remoteDaemon.path / subpath
          val supervisor = system.actorFor(message.getSupervisor).asInstanceOf[InternalActorRef]
          val actor = system.provider.actorOf(system, Props(creator = actorFactory), supervisor, path, true)
          addChild(subpath.mkString("/"), actor)
          system.deathWatch.subscribe(this, actor)
        case _ ⇒
          log.error("remote path does not match path from message [{}]", message)
      }
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
  //  private val tempNumber = new AtomicLong
  //  def tempName = "$_" + Helpers.base64(tempNumber.getAndIncrement())
  //  def tempPath = remote.remoteDaemon.path / tempName
  //
  //  // FIXME: handle real remote supervision, ticket #1408
  //  def handle_fun0_unit(message: RemoteSystemDaemonMessageProtocol) {
  //    new LocalActorRef(remote.system,
  //      Props(
  //        context ⇒ {
  //          case f: Function0[_] ⇒ try { f() } finally { context.self.stop() }
  //        }).copy(dispatcher = remote.computeGridDispatcher), remote.remoteDaemon, tempPath, systemService = true) ! payloadFor(message, classOf[Function0[Unit]])
  //  }
  //
  //  // FIXME: handle real remote supervision, ticket #1408
  //  def handle_fun0_any(message: RemoteSystemDaemonMessageProtocol, sender: ActorRef) {
  //    implicit val s = sender
  //    new LocalActorRef(remote.system,
  //      Props(
  //        context ⇒ {
  //          case f: Function0[_] ⇒ try { context.sender ! f() } finally { context.self.stop() }
  //        }).copy(dispatcher = remote.computeGridDispatcher), remote.remoteDaemon, tempPath, systemService = true) ! payloadFor(message, classOf[Function0[Any]])
  //  }
  //
  //  // FIXME: handle real remote supervision, ticket #1408
  //  def handle_fun1_arg_unit(message: RemoteSystemDaemonMessageProtocol) {
  //    new LocalActorRef(remote.system,
  //      Props(
  //        context ⇒ {
  //          case (fun: Function[_, _], param: Any) ⇒ try { fun.asInstanceOf[Any ⇒ Unit].apply(param) } finally { context.self.stop() }
  //        }).copy(dispatcher = remote.computeGridDispatcher), remote.remoteDaemon, tempPath, systemService = true) ! payloadFor(message, classOf[Tuple2[Function1[Any, Unit], Any]])
  //  }
  //
  //  // FIXME: handle real remote supervision, ticket #1408
  //  def handle_fun1_arg_any(message: RemoteSystemDaemonMessageProtocol, sender: ActorRef) {
  //    implicit val s = sender
  //    new LocalActorRef(remote.system,
  //      Props(
  //        context ⇒ {
  //          case (fun: Function[_, _], param: Any) ⇒ try { context.sender ! fun.asInstanceOf[Any ⇒ Any](param) } finally { context.self.stop() }
  //        }).copy(dispatcher = remote.computeGridDispatcher), remote.remoteDaemon, tempPath, systemService = true) ! payloadFor(message, classOf[Tuple2[Function1[Any, Any], Any]])
  //  }

  def handleFailover(message: RemoteSystemDaemonMessageProtocol) {
    // val (from, to) = payloadFor(message, classOf[(InetSocketremoteDaemonServiceName, InetSocketremoteDaemonServiceName)])
    // cluster.failOverClusterActorRefConnections(from, to)
  }

  private def payloadFor[T](message: RemoteSystemDaemonMessageProtocol, clazz: Class[T]): T = {
    remote.serialization.deserialize(message.getPayload.toByteArray, clazz, None) match {
      case Left(error)     ⇒ throw error
      case Right(instance) ⇒ instance.asInstanceOf[T]
    }
  }
}

class RemoteMessage(input: RemoteMessageProtocol, system: ActorSystemImpl, classLoader: Option[ClassLoader] = None) {

  def originalReceiver = input.getRecipient.getPath

  lazy val sender: ActorRef =
    if (input.hasSender) system.provider.actorFor(system.provider.rootGuardian, input.getSender.getPath)
    else system.deadLetters

  lazy val recipient: InternalActorRef = system.provider.actorFor(system.provider.rootGuardian, originalReceiver)

  lazy val payload: AnyRef = MessageSerializer.deserialize(system, input.getMessage, classLoader)

  override def toString = "RemoteMessage: " + payload + " to " + recipient + "<+{" + originalReceiver + "} from " + sender
}

trait RemoteMarshallingOps {

  def log: LoggingAdapter

  def system: ActorSystem

  def remote: Remote

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

    val remoteDaemon = remote.remoteDaemon

    remoteMessage.recipient match {
      case `remoteDaemon` ⇒
        remoteMessage.payload match {
          case m: RemoteSystemDaemonMessageProtocol ⇒
            implicit val timeout = system.settings.ActorTimeout
            try remoteDaemon ! m catch {
              case e: Exception ⇒ log.error(e, "exception while processing remote command {} from {}", m.getMessageType(), remoteMessage.sender)
            }
          case x ⇒ log.warning("remoteDaemon received illegal message {} from {}", x, remoteMessage.sender)
        }
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
      case r: RemoteActorRef ⇒
        implicit val t = remote.transports
        remoteMessage.originalReceiver match {
          case ParsedActorPath(address, _) if address == remote.remoteDaemon.path.address ⇒
            r.!(remoteMessage.payload)(remoteMessage.sender)
          case r ⇒ log.error("dropping message {} for non-local recipient {}", remoteMessage.payload, r)
        }
      case r ⇒ log.error("dropping message {} for non-local recipient {}", remoteMessage.payload, r)
    }
  }
}

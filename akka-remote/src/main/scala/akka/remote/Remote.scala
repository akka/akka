/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.actor._
import akka.event._
import akka.util._
import scala.util.duration._
import akka.util.Helpers._
import akka.serialization.{ JavaSerializer, Serialization, SerializationExtension }
import akka.dispatch.MessageDispatcher
import akka.dispatch.SystemMessage
import scala.annotation.tailrec
import akka.remote.RemoteProtocol.{ ActorRefProtocol, AkkaRemoteProtocol, RemoteControlProtocol, RemoteMessageProtocol }

/**
 * Remote module - contains remote client and server config, remote server instance, remote daemon, remote dispatchers etc.
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
  private var _transport: RemoteSupport[ParsedTransportAddress] = _
  def transport = _transport

  @volatile
  private var _provider: RemoteActorRefProvider = _
  def provider = _provider

  def init(system: ActorSystemImpl, provider: RemoteActorRefProvider) = {

    val log = Logging(system, "Remote")

    _provider = provider
    _serialization = SerializationExtension(system)
    _computeGridDispatcher = system.dispatchers.lookup("akka.remote.compute-grid-dispatcher")
    _remoteDaemon = new RemoteSystemDaemon(system, this, system.provider.rootPath / "remote", system.provider.rootGuardian, log)
    _eventStream = new NetworkEventStream(system)
    _transport = {
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

          remote.start(Option(Thread.currentThread().getContextClassLoader)) //TODO Any application loader here?

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

    log.info("Starting remote server on [{}@{}]", system.name, remoteAddress)
  }
}

sealed trait DaemonMsg
case class DaemonMsgCreate(factory: () ⇒ Actor, path: String, supervisor: ActorRef) extends DaemonMsg
case class DaemonMsgWatch(watcher: ActorRef, watched: ActorRef) extends DaemonMsg

/**
 * Internal system "daemon" actor for remote internal communication.
 *
 * It acts as the brain of the remote that responds to system remote events (messages) and undertakes action.
 */
class RemoteSystemDaemon(system: ActorSystemImpl, remote: Remote, _path: ActorPath, _parent: InternalActorRef, _log: LoggingAdapter)
  extends VirtualPathContainer(system.provider, _path, _parent, _log) {

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
      case (Nobody, _) ⇒ Nobody
      case (ref, 0)    ⇒ ref
      case (ref, n)    ⇒ ref.getChild(full.takeRight(n).iterator)
    }
  }

  override def !(msg: Any)(implicit sender: ActorRef = null): Unit = msg match {
    case message: DaemonMsg ⇒
      log.debug("Received command [{}] to RemoteSystemDaemon on [{}]", message, path.address.hostPort)
      message match {
        case DaemonMsgCreate(factory, path, supervisor) ⇒
          import remote.remoteAddress
          implicit val t = remote.transports

          path match {
            case ParsedActorPath(`remoteAddress`, elems) if elems.nonEmpty && elems.head == "remote" ⇒
              // TODO RK canonicalize path so as not to duplicate it always #1446
              val subpath = elems.drop(1)
              val path = remote.remoteDaemon.path / subpath
              val actor = system.provider.actorOf(system,
                Props(creator = factory),
                supervisor.asInstanceOf[InternalActorRef],
                path, true, None)
              addChild(subpath.mkString("/"), actor)
              system.deathWatch.subscribe(this, actor)
            case _ ⇒
              log.error("remote path does not match path from message [{}]", message)
          }
        case DaemonMsgWatch(watcher, watched) ⇒
          val other = system.actorFor(watcher.path.root / "remote")
          system.deathWatch.subscribe(other, watched)
      }

    case Terminated(child: LocalActorRef) ⇒ removeChild(child.path.elements.drop(1).mkString("/"))

    case t: Terminated                    ⇒ system.deathWatch.publish(t)

    case unknown                          ⇒ log.warning("Unknown message {} received by {}", unknown, this)
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
    if (remote.remoteSettings.LogReceivedMessages)
      log.debug("received message [{}]", remoteMessage)

    val remoteDaemon = remote.remoteDaemon

    remoteMessage.recipient match {
      case `remoteDaemon` ⇒
        remoteMessage.payload match {
          case m @ (_: DaemonMsg | _: Terminated) ⇒
            try remoteDaemon ! m catch {
              case e: Exception ⇒ log.error(e, "exception while processing remote command {} from {}", m, remoteMessage.sender)
            }
          case x ⇒ log.warning("remoteDaemon received illegal message {} from {}", x, remoteMessage.sender)
        }
      case l: LocalRef ⇒
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

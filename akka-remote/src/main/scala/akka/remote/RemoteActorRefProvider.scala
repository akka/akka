/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.actor._
import akka.dispatch._
import akka.event.Logging
import akka.serialization.Compression.LZF
import akka.remote.RemoteProtocol._
import akka.remote.RemoteProtocol.RemoteSystemDaemonMessageType._
import com.google.protobuf.ByteString
import akka.event.EventStream
import akka.serialization.SerializationExtension
import akka.serialization.Serialization

/**
 * Remote ActorRefProvider. Starts up actor on remote node and creates a RemoteActorRef representing it.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteActorRefProvider(
  val systemName: String,
  val settings: ActorSystem.Settings,
  val eventStream: EventStream,
  val scheduler: Scheduler,
  _deadLetters: InternalActorRef) extends ActorRefProvider {

  val log = Logging(eventStream, "RemoteActorRefProvider")

  val remoteSettings = new RemoteSettings(settings.config, systemName)

  def deathWatch = local.deathWatch
  def rootGuardian = local.rootGuardian
  def guardian = local.guardian
  def systemGuardian = local.systemGuardian
  def nodename = remoteSettings.NodeName
  def clustername = remoteSettings.ClusterName
  def terminationFuture = local.terminationFuture
  def dispatcher = local.dispatcher

  val deployer = new RemoteDeployer(settings)

  val rootPath: ActorPath = RootActorPath(RemoteAddress(systemName, remoteSettings.serverSettings.Hostname, remoteSettings.serverSettings.Port))

  private val local = new LocalActorRefProvider(systemName, settings, eventStream, scheduler, _deadLetters, rootPath, deployer)

  private var serialization: Serialization = _

  private var _remote: Remote = _
  def remote = _remote

  def init(system: ActorSystemImpl) {
    local.init(system)
    serialization = SerializationExtension(system)
    _remote = new Remote(system, nodename, remoteSettings)
    local.registerExtraNames(Map(("remote", remote.remoteDaemon)))
    terminationFuture.onComplete(_ ⇒ remote.server.shutdown())
  }

  def actorOf(system: ActorSystemImpl, props: Props, supervisor: InternalActorRef, name: String, systemService: Boolean): InternalActorRef =
    if (systemService) local.actorOf(system, props, supervisor, name, systemService)
    else {
      val path = supervisor.path / name

      @scala.annotation.tailrec
      def lookupRemotes(p: Iterable[String]): Option[DeploymentConfig.Deploy] = {
        p.headOption match {
          case None           ⇒ None
          case Some("remote") ⇒ lookupRemotes(p.drop(2))
          case Some("user")   ⇒ deployer.lookup(p.drop(1).mkString("/", "/", ""))
          case Some(_)        ⇒ None
        }
      }

      val elems = path.elements
      val deployment = (elems.head match {
        case "user"   ⇒ deployer.lookup(elems.drop(1).mkString("/", "/", ""))
        case "remote" ⇒ lookupRemotes(elems)
        case _        ⇒ None
      })

      deployment match {
        case Some(DeploymentConfig.Deploy(_, _, _, _, RemoteDeploymentConfig.RemoteScope(address))) ⇒

          if (address == rootPath.address) local.actorOf(system, props, supervisor, name)
          else {
            val rpath = RootActorPath(address) / "remote" / rootPath.address.hostPort / path.elements
            useActorOnNode(rpath, props.creator, supervisor)
            new RemoteActorRef(this, remote.server, rpath, supervisor, None)
          }

        case _ ⇒ local.actorOf(system, props, supervisor, name, systemService)
      }
    }

  def actorFor(path: ActorPath): InternalActorRef = path.root match {
    case `rootPath`                         ⇒ actorFor(rootGuardian, path.elements)
    case RootActorPath(_: RemoteAddress, _) ⇒ new RemoteActorRef(this, remote.server, path, Nobody, None)
    case _                                  ⇒ local.actorFor(path)
  }

  def actorFor(ref: InternalActorRef, path: String): InternalActorRef = path match {
    case RemoteActorPath(address, elems) ⇒
      if (address == rootPath.address) actorFor(rootGuardian, elems)
      else new RemoteActorRef(this, remote.server, new RootActorPath(address) / elems, Nobody, None)
    case _ ⇒ local.actorFor(ref, path)
  }

  def actorFor(ref: InternalActorRef, path: Iterable[String]): InternalActorRef = local.actorFor(ref, path)

  def ask(message: Any, recipient: ActorRef, within: Timeout): Future[Any] = local.ask(message, recipient, within)

  /**
   * Using (checking out) actor on a specific node.
   */
  def useActorOnNode(path: ActorPath, actorFactory: () ⇒ Actor, supervisor: ActorRef) {
    log.debug("[{}] Instantiating Remote Actor [{}]", rootPath, path)

    val actorFactoryBytes =
      serialization.serialize(actorFactory) match {
        case Left(error)  ⇒ throw error
        case Right(bytes) ⇒ if (remoteSettings.ShouldCompressData) LZF.compress(bytes) else bytes
      }

    val command = RemoteSystemDaemonMessageProtocol.newBuilder
      .setMessageType(USE)
      .setActorPath(path.toString)
      .setPayload(ByteString.copyFrom(actorFactoryBytes))
      .setSupervisor(supervisor.path.toString)
      .build()

    // we don’t wait for the ACK, because the remote end will process this command before any other message to the new actor
    actorFor(RootActorPath(path.address) / "remote") ! command
  }
}

/**
 * Remote ActorRef that is used when referencing the Actor on a different node than its "home" node.
 * This reference is network-aware (remembers its origin) and immutable.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] class RemoteActorRef private[akka] (
  provider: ActorRefProvider,
  remote: RemoteSupport,
  val path: ActorPath,
  val getParent: InternalActorRef,
  loader: Option[ClassLoader])
  extends InternalActorRef {

  def getChild(name: Iterator[String]): InternalActorRef = {
    val s = name.toStream
    s.headOption match {
      case None       ⇒ this
      case Some("..") ⇒ getParent getChild name
      case _          ⇒ new RemoteActorRef(provider, remote, path / s, Nobody, loader)
    }
  }

  @volatile
  private var running: Boolean = true

  def isTerminated: Boolean = !running

  def sendSystemMessage(message: SystemMessage): Unit = remote.send(message, None, this, loader)

  override def !(message: Any)(implicit sender: ActorRef = null): Unit = remote.send(message, Option(sender), this, loader)

  override def ?(message: Any)(implicit timeout: Timeout): Future[Any] = provider.ask(message, this, timeout)

  def suspend(): Unit = sendSystemMessage(Suspend())

  def resume(): Unit = sendSystemMessage(Resume())

  def stop(): Unit = sendSystemMessage(Terminate())

  def restart(cause: Throwable): Unit = sendSystemMessage(Recreate(cause))

  @throws(classOf[java.io.ObjectStreamException])
  private def writeReplace(): AnyRef = SerializedActorRef(path.toString)
}

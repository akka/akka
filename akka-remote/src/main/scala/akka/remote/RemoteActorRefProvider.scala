/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.actor._
import akka.dispatch._
import akka.util.duration._
import akka.util.Timeout
import akka.config.ConfigurationException
import akka.event.{ DeathWatch, Logging }
import akka.serialization.Compression.LZF
import akka.remote.RemoteProtocol._
import akka.remote.RemoteProtocol.RemoteSystemDaemonMessageType._
import com.google.protobuf.ByteString
import akka.event.EventStream
import akka.dispatch.Promise
import akka.config.ConfigurationException
import java.util.concurrent.{ TimeoutException }

/**
 * Remote ActorRefProvider. Starts up actor on remote node and creates a RemoteActorRef representing it.
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

  val remote = new Remote(settings, remoteSettings)
  implicit val transports = remote.transports

  val rootPath: ActorPath = RootActorPath(remote.remoteAddress)

  private val local = new LocalActorRefProvider(systemName, settings, eventStream, scheduler, _deadLetters, rootPath, deployer)

  def init(system: ActorSystemImpl) {
    local.init(system)
    remote.init(system, this)
    local.registerExtraNames(Map(("remote", remote.remoteDaemon)))
    terminationFuture.onComplete(_ ⇒ remote.server.shutdown())
  }

  def actorOf(system: ActorSystemImpl, props: Props, supervisor: InternalActorRef, path: ActorPath, systemService: Boolean, deploy: Option[Deploy]): InternalActorRef = {
    if (systemService) local.actorOf(system, props, supervisor, path, systemService, deploy)
    else {

      /*
       * This needs to deal with “mangled” paths, which are created by remote
       * deployment, also in this method. The scheme is the following:
       *
       * Whenever a remote deployment is found, create a path on that remote
       * address below “remote”, including the current system’s identification
       * as “sys@host:port” (typically; it will use whatever the remote
       * transport uses). This means that on a path up an actor tree each node
       * change introduces one layer or “remote/sys@host:port/” within the URI.
       *
       * Example:
       *
       * akka://sys@home:1234/remote/sys@remote:6667/remote/sys@other:3333/user/a/b/c
       *
       * means that the logical parent originates from “sys@other:3333” with
       * one child (may be “a” or “b”) being deployed on “sys@remote:6667” and
       * finally either “b” or “c” being created on “sys@home:1234”, where
       * this whole thing actually resides. Thus, the logical path is
       * “/user/a/b/c” and the physical path contains all remote placement
       * information.
       *
       * Deployments are always looked up using the logical path, which is the
       * purpose of the lookupRemotes internal method.
       */

      @scala.annotation.tailrec
      def lookupRemotes(p: Iterable[String]): Option[Deploy] = {
        p.headOption match {
          case None           ⇒ None
          case Some("remote") ⇒ lookupRemotes(p.drop(2))
          case Some("user")   ⇒ deployer.lookup(p.drop(1).mkString("/", "/", ""))
          case Some(_)        ⇒ None
        }
      }

      val elems = path.elements
      val deployment = deploy orElse (elems.head match {
        case "user"   ⇒ deployer.lookup(elems.drop(1).mkString("/", "/", ""))
        case "remote" ⇒ lookupRemotes(elems)
        case _        ⇒ None
      })

      deployment match {
        case Some(Deploy(_, _, _, _, RemoteScope(address))) ⇒
          // FIXME RK this should be done within the deployer, i.e. the whole parsing business
          address.parse(remote.transports) match {
            case Left(x) ⇒
              throw new ConfigurationException("cannot parse remote address: " + x)
            case Right(addr) ⇒
              if (addr == rootPath.address) local.actorOf(system, props, supervisor, path, false, deployment)
              else {
                val rpath = RootActorPath(addr) / "remote" / rootPath.address.hostPort / path.elements
                useActorOnNode(rpath, props.creator, supervisor)
                new RemoteActorRef(this, remote.server, rpath, supervisor, None)
              }
          }

        case _ ⇒ local.actorOf(system, props, supervisor, path, systemService, deployment)
      }
    }
  }

  def actorFor(path: ActorPath): InternalActorRef = path.root match {
    case `rootPath` ⇒ actorFor(rootGuardian, path.elements)
    case RootActorPath(_: RemoteSystemAddress[_], _) ⇒ new RemoteActorRef(this, remote.server, path, Nobody, None)
    case _ ⇒ local.actorFor(path)
  }

  def actorFor(ref: InternalActorRef, path: String): InternalActorRef = path match {
    case ParsedActorPath(address, elems) ⇒
      if (address == rootPath.address) actorFor(rootGuardian, elems)
      else new RemoteActorRef(this, remote.server, new RootActorPath(address) / elems, Nobody, None)
    case _ ⇒ local.actorFor(ref, path)
  }

  def actorFor(ref: InternalActorRef, path: Iterable[String]): InternalActorRef = local.actorFor(ref, path)

  def ask(within: Timeout): Option[AskActorRef] = local.ask(within)

  /**
   * Using (checking out) actor on a specific node.
   */
  def useActorOnNode(path: ActorPath, actorFactory: () ⇒ Actor, supervisor: ActorRef) {
    log.debug("[{}] Instantiating Remote Actor [{}]", rootPath, path)

    val actorFactoryBytes =
      remote.serialization.serialize(actorFactory) match {
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
 */
private[akka] class RemoteActorRef private[akka] (
  provider: RemoteActorRefProvider,
  remote: RemoteSupport[ParsedTransportAddress],
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

  override def ?(message: Any)(implicit timeout: Timeout): Future[Any] = {
    provider.ask(timeout) match {
      case Some(a) ⇒
        this.!(message)(a)
        a.result
      case None ⇒
        this.!(message)(null)
        Promise[Any]()(provider.dispatcher)
    }
  }

  def suspend(): Unit = sendSystemMessage(Suspend())

  def resume(): Unit = sendSystemMessage(Resume())

  def stop(): Unit = sendSystemMessage(Terminate())

  def restart(cause: Throwable): Unit = sendSystemMessage(Recreate(cause))

  @throws(classOf[java.io.ObjectStreamException])
  private def writeReplace(): AnyRef = SerializedActorRef(path.toString)
}

/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.actor._
import akka.dispatch._
import akka.event.{ DeathWatch, Logging, LoggingAdapter }
import akka.event.EventStream
import akka.serialization.Serialization
import akka.serialization.SerializationExtension

/**
 * Remote ActorRefProvider. Starts up actor on remote node and creates a RemoteActorRef representing it.
 */
private[akka] class RemoteActorRefProvider(
  val systemName: String,
  val settings: ActorSystem.Settings,
  val eventStream: EventStream,
  val scheduler: Scheduler,
  val dynamicAccess: DynamicAccess) extends ActorRefProvider {

  val remoteSettings: RemoteSettings = new RemoteSettings(settings.config, systemName)

  val deployer: RemoteDeployer = new RemoteDeployer(settings, dynamicAccess)

  private val local = new LocalActorRefProvider(systemName, settings, eventStream, scheduler, deployer)

  @volatile
  private var _log = local.log
  def log: LoggingAdapter = _log

  override def rootPath: ActorPath = local.rootPath
  override def deadLetters: InternalActorRef = local.deadLetters

  override val deathWatch: DeathWatch = new RemoteDeathWatch(local.deathWatch, this)

  // these are only available after init()
  override def rootGuardian: InternalActorRef = local.rootGuardian
  override def guardian: InternalActorRef = local.guardian
  override def systemGuardian: InternalActorRef = local.systemGuardian
  override def terminationFuture: Promise[Unit] = local.terminationFuture
  override def dispatcher: MessageDispatcher = local.dispatcher
  override def registerTempActor(actorRef: InternalActorRef, path: ActorPath): Unit = local.registerTempActor(actorRef, path)
  override def unregisterTempActor(path: ActorPath): Unit = local.unregisterTempActor(path)
  override def tempPath(): ActorPath = local.tempPath()
  override def tempContainer: VirtualPathContainer = local.tempContainer

  @volatile
  private var _transport: RemoteTransport = _
  def transport: RemoteTransport = _transport

  @volatile
  private var _serialization: Serialization = _
  def serialization: Serialization = _serialization

  @volatile
  private var _remoteDaemon: InternalActorRef = _
  def remoteDaemon: InternalActorRef = _remoteDaemon

  def init(system: ActorSystemImpl): Unit = {
    local.init(system)

    _remoteDaemon = new RemoteSystemDaemon(system, rootPath / "remote", rootGuardian, log)
    local.registerExtraNames(Map(("remote", remoteDaemon)))

    _serialization = SerializationExtension(system)

    _transport = {
      val fqn = remoteSettings.RemoteTransport
      val args = Seq(
        classOf[ExtendedActorSystem] -> system,
        classOf[RemoteActorRefProvider] -> this)

      system.dynamicAccess.createInstanceFor[RemoteTransport](fqn, args) match {
        case Left(problem) ⇒ throw new RemoteTransportException("Could not load remote transport layer " + fqn, problem)
        case Right(remote) ⇒ remote
      }
    }

    _log = Logging(eventStream, "RemoteActorRefProvider(" + transport.address + ")")

    // this enables reception of remote requests
    _transport.start()

    val remoteClientLifeCycleHandler = system.systemActorOf(Props(new Actor {
      def receive = {
        case RemoteClientError(cause, remote, address) ⇒ remote.shutdownClientConnection(address)
        case RemoteClientDisconnected(remote, address) ⇒ remote.shutdownClientConnection(address)
        case _                                         ⇒ //ignore other
      }
    }), "RemoteClientLifeCycleListener")

    system.eventStream.subscribe(remoteClientLifeCycleHandler, classOf[RemoteLifeCycleEvent])

    terminationFuture.onComplete(_ ⇒ transport.shutdown())
  }

  def actorOf(system: ActorSystemImpl, props: Props, supervisor: InternalActorRef, path: ActorPath,
              systemService: Boolean, deploy: Option[Deploy], lookupDeploy: Boolean): InternalActorRef = {
    if (systemService) local.actorOf(system, props, supervisor, path, systemService, deploy, lookupDeploy)
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
          case Some("user")   ⇒ deployer.lookup(p.drop(1))
          case Some(_)        ⇒ None
        }
      }

      val elems = path.elements
      val lookup =
        if (lookupDeploy)
          elems.head match {
            case "user"   ⇒ deployer.lookup(elems.drop(1))
            case "remote" ⇒ lookupRemotes(elems)
            case _        ⇒ None
          }
        else None

      val deployment = {
        deploy.toList ::: lookup.toList match {
          case Nil ⇒ Nil
          case l   ⇒ List(l reduce ((a, b) ⇒ b withFallback a))
        }
      }

      Iterator(props.deploy) ++ deployment.iterator reduce ((a, b) ⇒ b withFallback a) match {
        case d @ Deploy(_, _, _, RemoteScope(addr)) ⇒
          if (addr == rootPath.address || addr == transport.address) {
            local.actorOf(system, props, supervisor, path, false, deployment.headOption, false)
          } else {
            val rpath = RootActorPath(addr) / "remote" / transport.address.hostPort / path.elements
            useActorOnNode(rpath, props, d, supervisor)
            new RemoteActorRef(this, transport, rpath, supervisor)
          }

        case _ ⇒ local.actorOf(system, props, supervisor, path, systemService, deployment.headOption, false)
      }
    }
  }

  def actorFor(path: ActorPath): InternalActorRef =
    if (path.address == rootPath.address || path.address == transport.address) actorFor(rootGuardian, path.elements)
    else new RemoteActorRef(this, transport, path, Nobody)

  def actorFor(ref: InternalActorRef, path: String): InternalActorRef = path match {
    case ActorPathExtractor(address, elems) ⇒
      if (address == rootPath.address || address == transport.address) actorFor(rootGuardian, elems)
      else new RemoteActorRef(this, transport, new RootActorPath(address) / elems, Nobody)
    case _ ⇒ local.actorFor(ref, path)
  }

  def actorFor(ref: InternalActorRef, path: Iterable[String]): InternalActorRef = local.actorFor(ref, path)

  /**
   * Using (checking out) actor on a specific node.
   */
  def useActorOnNode(path: ActorPath, props: Props, deploy: Deploy, supervisor: ActorRef): Unit = {
    log.debug("[{}] Instantiating Remote Actor [{}]", rootPath, path)

    // we don’t wait for the ACK, because the remote end will process this command before any other message to the new actor
    actorFor(RootActorPath(path.address) / "remote") ! DaemonMsgCreate(props, deploy, path.toString, supervisor)
  }

  def getExternalAddressFor(addr: Address): Option[Address] = {
    val ta = transport.address
    val ra = rootPath.address
    addr match {
      case `ta` | `ra`                          ⇒ Some(rootPath.address)
      case Address("akka", _, Some(_), Some(_)) ⇒ Some(transport.address)
      case _                                    ⇒ None
    }
  }
}

private[akka] trait RemoteRef extends ActorRefScope {
  final def isLocal = false
}

/**
 * Remote ActorRef that is used when referencing the Actor on a different node than its "home" node.
 * This reference is network-aware (remembers its origin) and immutable.
 */
private[akka] class RemoteActorRef private[akka] (
  val provider: RemoteActorRefProvider,
  remote: RemoteTransport,
  val path: ActorPath,
  val getParent: InternalActorRef)
  extends InternalActorRef with RemoteRef {

  def getChild(name: Iterator[String]): InternalActorRef = {
    val s = name.toStream
    s.headOption match {
      case None       ⇒ this
      case Some("..") ⇒ getParent getChild name
      case _          ⇒ new RemoteActorRef(provider, remote, path / s, Nobody)
    }
  }

  @volatile
  private var running: Boolean = true

  def isTerminated: Boolean = !running

  def sendSystemMessage(message: SystemMessage): Unit = remote.send(message, None, this)

  override def !(message: Any)(implicit sender: ActorRef = null): Unit = remote.send(message, Option(sender), this)

  def suspend(): Unit = sendSystemMessage(Suspend())

  def resume(): Unit = sendSystemMessage(Resume())

  def stop(): Unit = sendSystemMessage(Terminate())

  def restart(cause: Throwable): Unit = sendSystemMessage(Recreate(cause))

  @throws(classOf[java.io.ObjectStreamException])
  private def writeReplace(): AnyRef = SerializedActorRef(path)
}

private[akka] class RemoteDeathWatch(val local: DeathWatch, val provider: RemoteActorRefProvider) extends DeathWatch {

  override def subscribe(watcher: ActorRef, watched: ActorRef): Boolean = watched match {
    case r: RemoteRef ⇒
      val ret = local.subscribe(watcher, watched)
      provider.actorFor(r.path.root / "remote") ! DaemonMsgWatch(watcher, watched)
      ret
    case l: LocalRef ⇒
      local.subscribe(watcher, watched)
    case _ ⇒
      provider.log.error("unknown ActorRef type {} as DeathWatch target", watched.getClass)
      false
  }

  override def unsubscribe(watcher: ActorRef, watched: ActorRef): Boolean = local.unsubscribe(watcher, watched)

  override def unsubscribe(watcher: ActorRef): Unit = local.unsubscribe(watcher)

  override def publish(event: Terminated): Unit = local.publish(event)
}

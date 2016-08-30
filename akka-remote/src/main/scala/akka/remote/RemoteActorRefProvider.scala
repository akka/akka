/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote

import akka.Done
import akka.actor._
import akka.dispatch.sysmsg._
import akka.event.{ EventStream, Logging, LoggingAdapter }
import akka.event.Logging.Error
import akka.serialization.{ Serialization, SerializationExtension }
import akka.pattern.pipe
import scala.util.control.NonFatal
import akka.actor.SystemGuardian.{ RegisterTerminationHook, TerminationHook, TerminationHookDone }
import scala.util.control.Exception.Catcher
import scala.concurrent.Future
import akka.ConfigurationException
import akka.dispatch.{ RequiresMessageQueue, UnboundedMessageQueueSemantics }
import akka.remote.artery.ArteryTransport
import akka.util.OptionVal
import akka.remote.artery.OutboundEnvelope

/**
 * INTERNAL API
 */
private[akka] object RemoteActorRefProvider {
  private final case class Internals(transport: RemoteTransport, serialization: Serialization, remoteDaemon: InternalActorRef)
    extends NoSerializationVerificationNeeded

  sealed trait TerminatorState
  case object Uninitialized extends TerminatorState
  case object Idle extends TerminatorState
  case object WaitDaemonShutdown extends TerminatorState
  case object WaitTransportShutdown extends TerminatorState
  case object Finished extends TerminatorState

  private class RemotingTerminator(systemGuardian: ActorRef) extends Actor with FSM[TerminatorState, Option[Internals]]
    with RequiresMessageQueue[UnboundedMessageQueueSemantics] {
    import context.dispatcher

    startWith(Uninitialized, None)

    when(Uninitialized) {
      case Event(i: Internals, _) ⇒
        systemGuardian ! RegisterTerminationHook
        goto(Idle) using Some(i)
    }

    when(Idle) {
      case Event(TerminationHook, Some(internals)) ⇒
        log.info("Shutting down remote daemon.")
        internals.remoteDaemon ! TerminationHook
        goto(WaitDaemonShutdown)
    }

    // TODO: state timeout
    when(WaitDaemonShutdown) {
      case Event(TerminationHookDone, Some(internals)) ⇒
        log.info("Remote daemon shut down; proceeding with flushing remote transports.")
        internals.transport.shutdown() pipeTo self
        goto(WaitTransportShutdown)
    }

    when(WaitTransportShutdown) {
      case Event(Done, _) ⇒
        log.info("Remoting shut down.")
        systemGuardian ! TerminationHookDone
        stop()

      case Event(Status.Failure(ex), _) ⇒
        log.error(ex, "Remoting shut down with error")
        systemGuardian ! TerminationHookDone
        stop()
    }

  }

  /*
   * Remoting wraps messages destined to a remote host in a remoting specific envelope: EndpointManager.Send
   * As these wrapped messages might arrive to the dead letters of an EndpointWriter, they need to be unwrapped
   * and handled as dead letters to the original (remote) destination. Without this special case, DeathWatch related
   * functionality breaks, like the special handling of Watch messages arriving to dead letters.
   */
  private class RemoteDeadLetterActorRef(
    _provider:    ActorRefProvider,
    _path:        ActorPath,
    _eventStream: EventStream) extends DeadLetterActorRef(_provider, _path, _eventStream) {
    import EndpointManager.Send

    override def !(message: Any)(implicit sender: ActorRef): Unit = message match {
      case Send(m, senderOption, _, seqOpt) ⇒
        // else ignore: it is a reliably delivered message that might be retried later, and it has not yet deserved
        // the dead letter status
        if (seqOpt.isEmpty) super.!(m)(senderOption.orNull)
      case DeadLetter(Send(m, senderOption, recipient, seqOpt), _, _) ⇒
        // else ignore: it is a reliably delivered message that might be retried later, and it has not yet deserved
        // the dead letter status
        if (seqOpt.isEmpty) super.!(m)(senderOption.orNull)
      case env: OutboundEnvelope ⇒
        super.!(env.message)(env.sender.orNull)
      case DeadLetter(env: OutboundEnvelope, _, _) ⇒
        super.!(env.message)(env.sender.orNull)
      case _ ⇒ super.!(message)(sender)
    }

    @throws(classOf[java.io.ObjectStreamException])
    override protected def writeReplace(): AnyRef = DeadLetterActorRef.serialized
  }
}

/**
 * INTERNAL API
 * Depending on this class is not supported, only the [[akka.actor.ActorRefProvider]] interface is supported.
 *
 * Remote ActorRefProvider. Starts up actor on remote node and creates a RemoteActorRef representing it.
 *
 */
private[akka] class RemoteActorRefProvider(
  val systemName:    String,
  val settings:      ActorSystem.Settings,
  val eventStream:   EventStream,
  val dynamicAccess: DynamicAccess) extends ActorRefProvider {
  import RemoteActorRefProvider._

  val remoteSettings: RemoteSettings = new RemoteSettings(settings.config)

  override val deployer: Deployer = createDeployer

  /**
   * Factory method to make it possible to override deployer in subclass
   * Creates a new instance every time
   */
  protected def createDeployer: RemoteDeployer = new RemoteDeployer(settings, dynamicAccess)

  private val local = new LocalActorRefProvider(systemName, settings, eventStream, dynamicAccess, deployer,
    Some(deadLettersPath ⇒ new RemoteDeadLetterActorRef(this, deadLettersPath, eventStream)))

  @volatile
  private var _log = local.log
  def log: LoggingAdapter = _log

  override def rootPath: ActorPath = local.rootPath
  override def deadLetters: InternalActorRef = local.deadLetters

  // these are only available after init()
  override def rootGuardian: InternalActorRef = local.rootGuardian
  override def guardian: LocalActorRef = local.guardian
  override def systemGuardian: LocalActorRef = local.systemGuardian
  override def terminationFuture: Future[Terminated] = local.terminationFuture
  override def registerTempActor(actorRef: InternalActorRef, path: ActorPath): Unit = local.registerTempActor(actorRef, path)
  override def unregisterTempActor(path: ActorPath): Unit = local.unregisterTempActor(path)
  override def tempPath(): ActorPath = local.tempPath()
  override def tempContainer: VirtualPathContainer = local.tempContainer

  @volatile private var _internals: Internals = _

  def transport: RemoteTransport = _internals.transport
  def serialization: Serialization = _internals.serialization
  def remoteDaemon: InternalActorRef = _internals.remoteDaemon

  // This actor ensures the ordering of shutdown between remoteDaemon and the transport
  @volatile private var remotingTerminator: ActorRef = _

  @volatile private var _remoteWatcher: ActorRef = _
  private[akka] def remoteWatcher = _remoteWatcher

  @volatile private var remoteDeploymentWatcher: ActorRef = _

  def init(system: ActorSystemImpl): Unit = {
    local.init(system)

    remotingTerminator = system.systemActorOf(
      remoteSettings.configureDispatcher(Props(classOf[RemotingTerminator], local.systemGuardian)),
      "remoting-terminator")

    val internals = Internals(
      remoteDaemon = {
      val d = new RemoteSystemDaemon(
        system,
        local.rootPath / "remote",
        rootGuardian,
        remotingTerminator,
        log,
        untrustedMode = remoteSettings.UntrustedMode)
      local.registerExtraNames(Map(("remote", d)))
      d
    },
      serialization = SerializationExtension(system),
      transport = if (remoteSettings.Artery.Enabled) new ArteryTransport(system, this) else new Remoting(system, this))

    _internals = internals
    remotingTerminator ! internals

    _log = Logging(eventStream, getClass.getName)

    // this enables reception of remote requests
    transport.start()

    _remoteWatcher = createRemoteWatcher(system)
    remoteDeploymentWatcher = createRemoteDeploymentWatcher(system)
  }

  protected def createRemoteWatcher(system: ActorSystemImpl): ActorRef = {
    import remoteSettings._
    val failureDetector = createRemoteWatcherFailureDetector(system)
    system.systemActorOf(
      configureDispatcher(
        RemoteWatcher.props(
          failureDetector,
          heartbeatInterval = WatchHeartBeatInterval,
          unreachableReaperInterval = WatchUnreachableReaperInterval,
          heartbeatExpectedResponseAfter = WatchHeartbeatExpectedResponseAfter)),
      "remote-watcher")
  }

  protected def createRemoteWatcherFailureDetector(system: ExtendedActorSystem): FailureDetectorRegistry[Address] = {
    def createFailureDetector(): FailureDetector =
      FailureDetectorLoader.load(remoteSettings.WatchFailureDetectorImplementationClass, remoteSettings.WatchFailureDetectorConfig, system)

    new DefaultFailureDetectorRegistry(() ⇒ createFailureDetector())
  }

  protected def createRemoteDeploymentWatcher(system: ActorSystemImpl): ActorRef =
    system.systemActorOf(remoteSettings.configureDispatcher(Props[RemoteDeploymentWatcher]()), "remote-deployment-watcher")

  def actorOf(system: ActorSystemImpl, props: Props, supervisor: InternalActorRef, path: ActorPath,
              systemService: Boolean, deploy: Option[Deploy], lookupDeploy: Boolean, async: Boolean): InternalActorRef =
    if (systemService) local.actorOf(system, props, supervisor, path, systemService, deploy, lookupDeploy, async)
    else {

      if (!system.dispatchers.hasDispatcher(props.dispatcher))
        throw new ConfigurationException(s"Dispatcher [${props.dispatcher}] not configured for path $path")

      /*
       * This needs to deal with “mangled” paths, which are created by remote
       * deployment, also in this method. The scheme is the following:
       *
       * Whenever a remote deployment is found, create a path on that remote
       * address below “remote”, including the current system’s identification
       * as “sys@host:port” (typically; it will use whatever the remote
       * transport uses). This means that on a path up an actor tree each node
       * change introduces one layer or “remote/scheme/sys@host:port/” within the URI.
       *
       * Example:
       *
       * akka.tcp://sys@home:1234/remote/akka/sys@remote:6667/remote/akka/sys@other:3333/user/a/b/c
       *
       * means that the logical parent originates from “akka.tcp://sys@other:3333” with
       * one child (may be “a” or “b”) being deployed on “akka.tcp://sys@remote:6667” and
       * finally either “b” or “c” being created on “akka.tcp://sys@home:1234”, where
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
          case Some("remote") ⇒ lookupRemotes(p.drop(3))
          case Some("user")   ⇒ deployer.lookup(p.drop(1))
          case Some(_)        ⇒ None
        }
      }

      val elems = path.elements
      val lookup =
        if (lookupDeploy)
          elems.head match {
            case "user" | "system" ⇒ deployer.lookup(elems.drop(1))
            case "remote"          ⇒ lookupRemotes(elems)
            case _                 ⇒ None
          }
        else None

      val deployment = {
        deploy.toList ::: lookup.toList match {
          case Nil ⇒ Nil
          case l   ⇒ List(l reduce ((a, b) ⇒ b withFallback a))
        }
      }

      Iterator(props.deploy) ++ deployment.iterator reduce ((a, b) ⇒ b withFallback a) match {
        case d @ Deploy(_, _, _, RemoteScope(addr), _, _) ⇒
          if (hasAddress(addr)) {
            local.actorOf(system, props, supervisor, path, false, deployment.headOption, false, async)
          } else if (props.deploy.scope == LocalScope) {
            throw new ConfigurationException(s"configuration requested remote deployment for local-only Props at [$path]")
          } else try {
            try {
              // for consistency we check configuration of dispatcher and mailbox locally
              val dispatcher = system.dispatchers.lookup(props.dispatcher)
              system.mailboxes.getMailboxType(props, dispatcher.configurator.config)
            } catch {
              case NonFatal(e) ⇒ throw new ConfigurationException(
                s"configuration problem while creating [$path] with dispatcher [${props.dispatcher}] and mailbox [${props.mailbox}]", e)
            }
            val localAddress = transport.localAddressForRemote(addr)
            val rpath = (RootActorPath(addr) / "remote" / localAddress.protocol / localAddress.hostPort / path.elements).
              withUid(path.uid)
            new RemoteActorRef(transport, localAddress, rpath, supervisor, Some(props), Some(d))
          } catch {
            case NonFatal(e) ⇒ throw new IllegalArgumentException(s"remote deployment failed for [$path]", e)
          }

        case _ ⇒
          local.actorOf(system, props, supervisor, path, systemService, deployment.headOption, false, async)
      }
    }

  @deprecated("use actorSelection instead of actorFor", "2.2")
  override private[akka] def actorFor(path: ActorPath): InternalActorRef = {
    if (hasAddress(path.address)) actorFor(rootGuardian, path.elements)
    else try {
      new RemoteActorRef(transport, transport.localAddressForRemote(path.address),
        path, Nobody, props = None, deploy = None)
    } catch {
      case NonFatal(e) ⇒
        log.error(e, "Error while looking up address [{}]", path.address)
        new EmptyLocalActorRef(this, path, eventStream)
    }
  }

  @deprecated("use actorSelection instead of actorFor", "2.2")
  override private[akka] def actorFor(ref: InternalActorRef, path: String): InternalActorRef = path match {
    case ActorPathExtractor(address, elems) ⇒
      if (hasAddress(address)) actorFor(rootGuardian, elems)
      else {
        val rootPath = RootActorPath(address) / elems
        try {
          new RemoteActorRef(transport, transport.localAddressForRemote(address),
            rootPath, Nobody, props = None, deploy = None)
        } catch {
          case NonFatal(e) ⇒
            log.error(e, "Error while looking up address [{}]", rootPath.address)
            new EmptyLocalActorRef(this, rootPath, eventStream)
        }
      }
    case _ ⇒ local.actorFor(ref, path)
  }

  @deprecated("use actorSelection instead of actorFor", "2.2")
  override private[akka] def actorFor(ref: InternalActorRef, path: Iterable[String]): InternalActorRef =
    local.actorFor(ref, path)

  def rootGuardianAt(address: Address): ActorRef = {
    if (hasAddress(address)) rootGuardian
    else try {
      new RemoteActorRef(transport, transport.localAddressForRemote(address),
        RootActorPath(address), Nobody, props = None, deploy = None)
    } catch {
      case NonFatal(e) ⇒
        log.error(e, "No root guardian at [{}]", address)
        new EmptyLocalActorRef(this, RootActorPath(address), eventStream)
    }
  }

  /**
   * INTERNAL API
   * Called in deserialization of incoming remote messages where the correct local address is known.
   */
  private[akka] def resolveActorRefWithLocalAddress(path: String, localAddress: Address): InternalActorRef = {
    path match {
      case ActorPathExtractor(address, elems) ⇒
        if (hasAddress(address))
          local.resolveActorRef(rootGuardian, elems)
        else try {
          new RemoteActorRef(transport, localAddress, RootActorPath(address) / elems, Nobody, props = None, deploy = None)
        } catch {
          case NonFatal(e) ⇒
            log.warning("Error while resolving ActorRef [{}] due to [{}]", path, e.getMessage)
            new EmptyLocalActorRef(this, RootActorPath(address) / elems, eventStream)
        }
      case _ ⇒
        log.debug("resolve of unknown path [{}] failed", path)
        deadLetters
    }
  }

  def resolveActorRef(path: String): ActorRef = path match {
    case ActorPathExtractor(address, elems) ⇒
      if (hasAddress(address)) local.resolveActorRef(rootGuardian, elems)
      else {
        val rootPath = RootActorPath(address) / elems
        try {
          new RemoteActorRef(transport, transport.localAddressForRemote(address),
            rootPath, Nobody, props = None, deploy = None)
        } catch {
          case NonFatal(e) ⇒
            log.warning("Error while resolving ActorRef [{}] due to [{}]", path, e.getMessage)
            new EmptyLocalActorRef(this, rootPath, eventStream)
        }
      }
    case _ ⇒
      log.debug("resolve of unknown path [{}] failed", path)
      deadLetters
  }

  def resolveActorRef(path: ActorPath): ActorRef = {
    if (hasAddress(path.address)) local.resolveActorRef(rootGuardian, path.elements)
    else try {
      new RemoteActorRef(transport, transport.localAddressForRemote(path.address),
        path, Nobody, props = None, deploy = None)
    } catch {
      case NonFatal(e) ⇒
        log.warning("Error while resolving ActorRef [{}] due to [{}]", path, e.getMessage)
        new EmptyLocalActorRef(this, path, eventStream)
    }
  }

  /**
   * Using (checking out) actor on a specific node.
   */
  def useActorOnNode(ref: ActorRef, props: Props, deploy: Deploy, supervisor: ActorRef): Unit = {
    log.debug("[{}] Instantiating Remote Actor [{}]", rootPath, ref.path)

    // we don’t wait for the ACK, because the remote end will process this command before any other message to the new actor
    // actorSelection can't be used here because then it is not guaranteed that the actor is created
    // before someone can send messages to it
    resolveActorRef(RootActorPath(ref.path.address) / "remote") !
      DaemonMsgCreate(props, deploy, ref.path.toSerializationFormat, supervisor)

    remoteDeploymentWatcher ! RemoteDeploymentWatcher.WatchRemote(ref, supervisor)
  }

  def getExternalAddressFor(addr: Address): Option[Address] = {
    addr match {
      case _ if hasAddress(addr)           ⇒ Some(local.rootPath.address)
      case Address(_, _, Some(_), Some(_)) ⇒ try Some(transport.localAddressForRemote(addr)) catch { case NonFatal(_) ⇒ None }
      case _                               ⇒ None
    }
  }

  def getDefaultAddress: Address = transport.defaultAddress

  private def hasAddress(address: Address): Boolean =
    address == local.rootPath.address || address == rootPath.address || transport.addresses(address)

  /**
   * Marks a remote system as out of sync and prevents reconnects until the quarantine timeout elapses.
   *
   * @param address Address of the remote system to be quarantined
   * @param uid UID of the remote system, if the uid is not defined it will not be a strong quarantine but
   *   the current endpoint writer will be stopped (dropping system messages) and the address will be gated
   */
  def quarantine(address: Address, uid: Option[Int]): Unit = transport.quarantine(address, uid)

}

private[akka] trait RemoteRef extends ActorRefScope {
  final def isLocal = false
}

/**
 * INTERNAL API
 * Remote ActorRef that is used when referencing the Actor on a different node than its "home" node.
 * This reference is network-aware (remembers its origin) and immutable.
 */
private[akka] class RemoteActorRef private[akka] (
  remote:                RemoteTransport,
  val localAddressToUse: Address,
  val path:              ActorPath,
  val getParent:         InternalActorRef,
  props:                 Option[Props],
  deploy:                Option[Deploy])
  extends InternalActorRef with RemoteRef {

  remote match {
    case t: ArteryTransport ⇒
      // detect mistakes such as using "akka.tcp" with Artery
      if (path.address.protocol != t.localAddress.address.protocol)
        throw new IllegalArgumentException(
          s"Wrong protocol of [${path}], expected [${t.localAddress.address.protocol}]")
    case _ ⇒
  }
  @volatile private[remote] var cachedAssociation: artery.Association = null

  // used by artery to direct messages to separate specialized streams
  @volatile private[remote] var cachedSendQueueIndex: Int = -1

  def getChild(name: Iterator[String]): InternalActorRef = {
    val s = name.toStream
    s.headOption match {
      case None       ⇒ this
      case Some("..") ⇒ getParent getChild name
      case _          ⇒ new RemoteActorRef(remote, localAddressToUse, path / s, Nobody, props = None, deploy = None)
    }
  }

  @deprecated("Use context.watch(actor) and receive Terminated(actor)", "2.2")
  override private[akka] def isTerminated: Boolean = false

  private def handleException(message: Any, sender: ActorRef): Catcher[Unit] = {
    case e: InterruptedException ⇒
      remote.system.eventStream.publish(Error(e, path.toString, getClass, "interrupted during message send"))
      remote.system.deadLetters.tell(message, sender)
      Thread.currentThread.interrupt()
    case NonFatal(e) ⇒
      remote.system.eventStream.publish(Error(e, path.toString, getClass, "swallowing exception during message send"))
      remote.system.deadLetters.tell(message, sender)
  }

  /**
   * Determine if a watch/unwatch message must be handled by the remoteWatcher actor, or sent to this remote ref
   */
  def isWatchIntercepted(watchee: ActorRef, watcher: ActorRef) =
    if (watchee.path.uid == akka.actor.ActorCell.undefinedUid) {
      provider.log.debug("actorFor is deprecated, and watching a remote ActorRef acquired with actorFor is not reliable: [{}]", watchee.path)
      false // Not managed by the remote watcher, so not reliable to communication failure or remote system crash
    } else {
      // If watchee != this then watcher should == this. This is a reverse watch, and it is not intercepted
      // If watchee == this, only the watches from remoteWatcher are sent on the wire, on behalf of other watchers
      watcher != provider.remoteWatcher && watchee == this
    }

  def sendSystemMessage(message: SystemMessage): Unit =
    try {
      //send to remote, unless watch message is intercepted by the remoteWatcher
      message match {
        case Watch(watchee, watcher) if isWatchIntercepted(watchee, watcher) ⇒
          provider.remoteWatcher ! RemoteWatcher.WatchRemote(watchee, watcher)
        //Unwatch has a different signature, need to pattern match arguments against InternalActorRef
        case Unwatch(watchee: InternalActorRef, watcher: InternalActorRef) if isWatchIntercepted(watchee, watcher) ⇒
          provider.remoteWatcher ! RemoteWatcher.UnwatchRemote(watchee, watcher)
        case _ ⇒ remote.send(message, OptionVal.None, this)
      }
    } catch handleException(message, Actor.noSender)

  override def !(message: Any)(implicit sender: ActorRef = Actor.noSender): Unit = {
    if (message == null) throw new InvalidMessageException("Message is null")
    try remote.send(message, OptionVal(sender), this) catch handleException(message, sender)
  }

  override def provider: RemoteActorRefProvider = remote.provider

  def start(): Unit =
    if (props.isDefined && deploy.isDefined) remote.provider.useActorOnNode(this, props.get, deploy.get, getParent)

  def suspend(): Unit = sendSystemMessage(Suspend())

  def resume(causedByFailure: Throwable): Unit = sendSystemMessage(Resume(causedByFailure))

  def stop(): Unit = sendSystemMessage(Terminate())

  def restart(cause: Throwable): Unit = sendSystemMessage(Recreate(cause))

  @throws(classOf[java.io.ObjectStreamException])
  private def writeReplace(): AnyRef = SerializedActorRef(this)
}

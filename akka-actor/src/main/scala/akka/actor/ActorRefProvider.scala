/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.actor

import akka.dispatch.sysmsg._
import akka.dispatch.{ UnboundedMessageQueueSemantics, RequiresMessageQueue }
import akka.routing._
import akka.event._
import akka.util.{ Helpers }
import akka.japi.Util.immutableSeq
import akka.util.Collections.EmptyImmutableSeq
import scala.util.control.NonFatal
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.{ ExecutionContextExecutor, Future, Promise }
import scala.annotation.implicitNotFound
import akka.ConfigurationException
import akka.dispatch.Mailboxes

/**
 * Interface for all ActorRef providers to implement.
 */
trait ActorRefProvider {

  /**
   * Reference to the supervisor of guardian and systemGuardian; this is
   * exposed so that the ActorSystemImpl can use it as lookupRoot, i.e.
   * for anchoring absolute actor look-ups.
   */
  def rootGuardian: InternalActorRef

  /**
   * Reference to the supervisor of guardian and systemGuardian at the specified address;
   * this is exposed so that the ActorRefFactory can use it as lookupRoot, i.e.
   * for anchoring absolute actor selections.
   */
  def rootGuardianAt(address: Address): ActorRef

  /**
   * Reference to the supervisor used for all top-level user actors.
   */
  def guardian: LocalActorRef

  /**
   * Reference to the supervisor used for all top-level system actors.
   */
  def systemGuardian: LocalActorRef

  /**
   * Dead letter destination for this provider.
   */
  def deadLetters: ActorRef

  /**
   * The root path for all actors within this actor system, not including any remote address information.
   */
  def rootPath: ActorPath

  /**
   * The Settings associated with this ActorRefProvider
   */
  def settings: ActorSystem.Settings

  /**
   * Initialization of an ActorRefProvider happens in two steps: first
   * construction of the object with settings, eventStream, etc.
   * and then—when the ActorSystem is constructed—the second phase during
   * which actors may be created (e.g. the guardians).
   */
  def init(system: ActorSystemImpl): Unit

  /**
   * The Deployer associated with this ActorRefProvider
   */
  def deployer: Deployer

  /**
   * Generates and returns a unique actor path below “/temp”.
   */
  def tempPath(): ActorPath

  /**
   * Returns the actor reference representing the “/temp” path.
   */
  def tempContainer: InternalActorRef

  /**
   * Registers an actorRef at a path returned by tempPath(); do NOT pass in any other path.
   */
  def registerTempActor(actorRef: InternalActorRef, path: ActorPath): Unit

  /**
   * Unregister a temporary actor from the “/temp” path (i.e. obtained from tempPath()); do NOT pass in any other path.
   */
  def unregisterTempActor(path: ActorPath): Unit

  /**
   * Actor factory with create-only semantics: will create an actor as
   * described by props with the given supervisor and path (may be different
   * in case of remote supervision). If systemService is true, deployment is
   * bypassed (local-only). If ``Some(deploy)`` is passed in, it should be
   * regarded as taking precedence over the nominally applicable settings,
   * but it should be overridable from external configuration; the lookup of
   * the latter can be suppressed by setting ``lookupDeploy`` to ``false``.
   */
  def actorOf(
    system:        ActorSystemImpl,
    props:         Props,
    supervisor:    InternalActorRef,
    path:          ActorPath,
    systemService: Boolean,
    deploy:        Option[Deploy],
    lookupDeploy:  Boolean,
    async:         Boolean): InternalActorRef

  /**
   * INTERNAL API
   *
   * Create actor reference for a specified local or remote path. If no such
   * actor exists, it will be (equivalent to) a dead letter reference.
   *
   * Actor references acquired with `actorFor` do not always include the full information
   * about the underlying actor identity and therefore such references do not always compare
   * equal to references acquired with `actorOf`, `sender`, or `context.self`.
   */
  @deprecated("use actorSelection instead of actorFor", "2.2")
  private[akka] def actorFor(path: ActorPath): InternalActorRef

  /**
   * INTERNAL API
   *
   * Create actor reference for a specified local or remote path, which will
   * be parsed using java.net.URI. If no such actor exists, it will be
   * (equivalent to) a dead letter reference. If `s` is a relative URI, resolve
   * it relative to the given ref.
   */
  @deprecated("use actorSelection instead of actorFor", "2.2")
  private[akka] def actorFor(ref: InternalActorRef, s: String): InternalActorRef

  /**
   * INTERNAL API
   *
   * Create actor reference for the specified child path starting at the
   * given starting point. This method always returns an actor which is “logically local”,
   * i.e. it cannot be used to obtain a reference to an actor which is not
   * physically or logically attached to this actor system.
   */
  @deprecated("use actorSelection instead of actorFor", "2.2")
  private[akka] def actorFor(ref: InternalActorRef, p: Iterable[String]): InternalActorRef

  /**
   * Create actor reference for a specified path. If no such
   * actor exists, it will be (equivalent to) a dead letter reference.
   */
  def resolveActorRef(path: String): ActorRef

  /**
   * Create actor reference for a specified path. If no such
   * actor exists, it will be (equivalent to) a dead letter reference.
   */
  def resolveActorRef(path: ActorPath): ActorRef

  /**
   * This Future is completed upon termination of this ActorRefProvider, which
   * is usually initiated by stopping the guardian via ActorSystem.stop().
   */
  def terminationFuture: Future[Terminated]

  /**
   * Obtain the address which is to be used within sender references when
   * sending to the given other address or none if the other address cannot be
   * reached from this system (i.e. no means of communication known; no
   * attempt is made to verify actual reachability).
   */
  def getExternalAddressFor(addr: Address): Option[Address]

  /**
   * Obtain the external address of the default transport.
   */
  def getDefaultAddress: Address
}

/**
 * Interface implemented by ActorSystem and ActorContext, the only two places
 * from which you can get fresh actors.
 */
@implicitNotFound("implicit ActorRefFactory required: if outside of an Actor you need an implicit ActorSystem, inside of an actor this should be the implicit ActorContext")
trait ActorRefFactory {
  /**
   * INTERNAL API
   */
  protected def systemImpl: ActorSystemImpl
  /**
   * INTERNAL API
   */
  protected def provider: ActorRefProvider

  /**
   * Returns the default MessageDispatcher associated with this ActorRefFactory
   */
  implicit def dispatcher: ExecutionContextExecutor

  /**
   * Father of all children created by this interface.
   *
   * INTERNAL API
   */
  protected def guardian: InternalActorRef

  /**
   * INTERNAL API
   */
  protected def lookupRoot: InternalActorRef

  /**
   * Create new actor as child of this context and give it an automatically
   * generated name (currently similar to base64-encoded integer count,
   * reversed and with “$” prepended, may change in the future).
   *
   * See [[akka.actor.Props]] for details on how to obtain a `Props` object.
   *
   * @throws akka.ConfigurationException if deployment, dispatcher
   *   or mailbox configuration is wrong
   * @throws UnsupportedOperationException if invoked on an ActorSystem that
   *   uses a custom user guardian
   */
  def actorOf(props: Props): ActorRef

  /**
   * Create new actor as child of this context with the given name, which must
   * not be null, empty or start with “$”. If the given name is already in use,
   * an `InvalidActorNameException` is thrown.
   *
   * See [[akka.actor.Props]] for details on how to obtain a `Props` object.
   *
   * @throws akka.actor.InvalidActorNameException if the given name is
   *   invalid or already in use
   * @throws akka.ConfigurationException if deployment, dispatcher
   *   or mailbox configuration is wrong
   * @throws UnsupportedOperationException if invoked on an ActorSystem that
   *   uses a custom user guardian
   */
  def actorOf(props: Props, name: String): ActorRef

  /**
   * INTERNAL API
   *
   * Look-up an actor by path; if it does not exist, returns a reference to
   * the dead-letter mailbox of the [[akka.actor.ActorSystem]]. If the path
   * point to an actor which is not local, no attempt is made during this
   * call to verify that the actor it represents does exist or is alive; use
   * `watch(ref)` to be notified of the target’s termination, which is also
   * signaled if the queried path cannot be resolved.
   *
   * Actor references acquired with `actorFor` do not always include the full information
   * about the underlying actor identity and therefore such references do not always compare
   * equal to references acquired with `actorOf`, `sender`, or `context.self`.
   */
  @deprecated("use actorSelection instead of actorFor", "2.2")
  private[akka] def actorFor(path: ActorPath): ActorRef = provider.actorFor(path)

  /**
   * INTERNAL API
   *
   * Look-up an actor by path represented as string.
   *
   * Absolute URIs like `akka://appname/user/actorA` are looked up as described
   * for look-ups by `actorOf(ActorPath)`.
   *
   * Relative URIs like `/service/actorA/childB` are looked up relative to the
   * root path of the [[akka.actor.ActorSystem]] containing this factory and as
   * described for look-ups by `actorOf(Iterable[String])`.
   *
   * Relative URIs like `myChild/grandChild` or `../myBrother` are looked up
   * relative to the current context as described for look-ups by
   * `actorOf(Iterable[String])`
   *
   * Actor references acquired with `actorFor` do not always include the full information
   * about the underlying actor identity and therefore such references do not always compare
   * equal to references acquired with `actorOf`, `sender`, or `context.self`.
   */
  @deprecated("use actorSelection instead of actorFor", "2.2")
  private[akka] def actorFor(path: String): ActorRef = provider.actorFor(lookupRoot, path)

  /**
   * INTERNAL API
   *
   * Look-up an actor by applying the given path elements, starting from the
   * current context, where `".."` signifies the parent of an actor.
   *
   * Example:
   * {{{
   * class MyActor extends Actor {
   *   def receive = {
   *     case msg =>
   *       ...
   *       val target = context.actorFor(Seq("..", "myBrother", "myNephew"))
   *       ...
   *   }
   * }
   * }}}
   *
   * For maximum performance use a collection with efficient head & tail operations.
   *
   * Actor references acquired with `actorFor` do not always include the full information
   * about the underlying actor identity and therefore such references do not always compare
   * equal to references acquired with `actorOf`, `sender`, or `context.self`.
   */
  @deprecated("use actorSelection instead of actorFor", "2.2")
  private[akka] def actorFor(path: Iterable[String]): ActorRef = provider.actorFor(lookupRoot, path)

  /**
   * INTERNAL API
   *
   * Java API: Look-up an actor by applying the given path elements, starting from the
   * current context, where `".."` signifies the parent of an actor.
   *
   * Example:
   * {{{
   * public class MyActor extends UntypedActor {
   *   public void onReceive(Object msg) throws Exception {
   *     ...
   *     final List<String> path = new ArrayList<String>();
   *     path.add("..");
   *     path.add("myBrother");
   *     path.add("myNephew");
   *     final ActorRef target = getContext().actorFor(path);
   *     ...
   *   }
   * }
   * }}}
   *
   * For maximum performance use a collection with efficient head & tail operations.
   *
   * Actor references acquired with `actorFor` do not always include the full information
   * about the underlying actor identity and therefore such references do not always compare
   * equal to references acquired with `actorOf`, `sender`, or `context.self`.
   */
  @deprecated("use actorSelection instead of actorFor", "2.2")
  private[akka] def actorFor(path: java.lang.Iterable[String]): ActorRef = provider.actorFor(lookupRoot, immutableSeq(path))

  /**
   * Construct an [[akka.actor.ActorSelection]] from the given path, which is
   * parsed for wildcards (these are replaced by regular expressions
   * internally). No attempt is made to verify the existence of any part of
   * the supplied path, it is recommended to send a message and gather the
   * replies in order to resolve the matching set of actors.
   */
  def actorSelection(path: String): ActorSelection = path match {
    case RelativeActorPath(elems) ⇒
      if (elems.isEmpty) ActorSelection(provider.deadLetters, "")
      else if (elems.head.isEmpty) ActorSelection(provider.rootGuardian, elems.tail)
      else ActorSelection(lookupRoot, elems)
    case ActorPathExtractor(address, elems) ⇒
      ActorSelection(provider.rootGuardianAt(address), elems)
    case _ ⇒
      ActorSelection(provider.deadLetters, "")
  }

  /**
   * Construct an [[akka.actor.ActorSelection]] from the given path, which is
   * parsed for wildcards (these are replaced by regular expressions
   * internally). No attempt is made to verify the existence of any part of
   * the supplied path, it is recommended to send a message and gather the
   * replies in order to resolve the matching set of actors.
   */
  def actorSelection(path: ActorPath): ActorSelection =
    ActorSelection(provider.rootGuardianAt(path.address), path.elements)

  /**
   * Stop the actor pointed to by the given [[akka.actor.ActorRef]]; this is
   * an asynchronous operation, i.e. involves a message send.
   * If this method is applied to the `self` reference from inside an Actor
   * then that Actor is guaranteed to not process any further messages after
   * this call; please note that the processing of the current message will
   * continue, this method does not immediately terminate this actor.
   */
  def stop(actor: ActorRef): Unit
}

/**
 * Internal Akka use only, used in implementation of system.stop(child).
 */
private[akka] final case class StopChild(child: ActorRef)

/**
 * INTERNAL API
 */
private[akka] object SystemGuardian {
  /**
   * For the purpose of orderly shutdown it's possible
   * to register interest in the termination of systemGuardian
   * and receive a notification `TerminationHook`
   * before systemGuardian is stopped. The registered hook is supposed
   * to reply with `TerminationHookDone` and the
   * systemGuardian will not stop until all registered hooks have replied.
   */
  case object RegisterTerminationHook
  case object TerminationHook
  case object TerminationHookDone
}

private[akka] object LocalActorRefProvider {

  /*
   * Root and user guardian
   */
  private class Guardian(override val supervisorStrategy: SupervisorStrategy) extends Actor
    with RequiresMessageQueue[UnboundedMessageQueueSemantics] {

    def receive = {
      case Terminated(_)    ⇒ context.stop(self)
      case StopChild(child) ⇒ context.stop(child)
    }

    // guardian MUST NOT lose its children during restart
    override def preRestart(cause: Throwable, msg: Option[Any]) {}
  }

  /**
   * System guardian
   */
  private class SystemGuardian(override val supervisorStrategy: SupervisorStrategy, val guardian: ActorRef)
    extends Actor with RequiresMessageQueue[UnboundedMessageQueueSemantics] {
    import SystemGuardian._

    var terminationHooks = Set.empty[ActorRef]

    def receive = {
      case Terminated(`guardian`) ⇒
        // time for the systemGuardian to stop, but first notify all the
        // termination hooks, they will reply with TerminationHookDone
        // and when all are done the systemGuardian is stopped
        context.become(terminating)
        terminationHooks foreach { _ ! TerminationHook }
        stopWhenAllTerminationHooksDone()
      case Terminated(a) ⇒
        // a registered, and watched termination hook terminated before
        // termination process of guardian has started
        terminationHooks -= a
      case StopChild(child) ⇒ context.stop(child)
      case RegisterTerminationHook if sender() != context.system.deadLetters ⇒
        terminationHooks += sender()
        context watch sender()
    }

    def terminating: Receive = {
      case Terminated(a)       ⇒ stopWhenAllTerminationHooksDone(a)
      case TerminationHookDone ⇒ stopWhenAllTerminationHooksDone(sender())
    }

    def stopWhenAllTerminationHooksDone(remove: ActorRef): Unit = {
      terminationHooks -= remove
      stopWhenAllTerminationHooksDone()
    }

    def stopWhenAllTerminationHooksDone(): Unit =
      if (terminationHooks.isEmpty) {
        context.system.eventStream.stopDefaultLoggers(context.system)
        context.stop(self)
      }

    // guardian MUST NOT lose its children during restart
    override def preRestart(cause: Throwable, msg: Option[Any]) {}
  }

}

/**
 * Local ActorRef provider.
 *
 * INTERNAL API!
 *
 * Depending on this class is not supported, only the [[ActorRefProvider]] interface is supported.
 */
private[akka] class LocalActorRefProvider private[akka] (
  _systemName:           String,
  override val settings: ActorSystem.Settings,
  val eventStream:       EventStream,
  val dynamicAccess:     DynamicAccess,
  override val deployer: Deployer,
  _deadLetters:          Option[ActorPath ⇒ InternalActorRef])
  extends ActorRefProvider {

  // this is the constructor needed for reflectively instantiating the provider
  def this(
    _systemName:   String,
    settings:      ActorSystem.Settings,
    eventStream:   EventStream,
    dynamicAccess: DynamicAccess) =
    this(
      _systemName,
      settings,
      eventStream,
      dynamicAccess,
      new Deployer(settings, dynamicAccess),
      None)

  override val rootPath: ActorPath = RootActorPath(Address("akka", _systemName))

  private[akka] val log: MarkerLoggingAdapter = Logging.withMarker(eventStream, getClass.getName + "(" + rootPath.address + ")")

  override val deadLetters: InternalActorRef =
    _deadLetters.getOrElse((p: ActorPath) ⇒ new DeadLetterActorRef(this, p, eventStream)).apply(rootPath / "deadLetters")

  private[this] final val terminationPromise: Promise[Terminated] = Promise[Terminated]()

  def terminationFuture: Future[Terminated] = terminationPromise.future

  /*
   * generate name for temporary actor refs
   */
  private val tempNumber = new AtomicLong

  private val tempNode = rootPath / "temp"

  override def tempPath(): ActorPath = tempNode / Helpers.base64(tempNumber.getAndIncrement())

  /**
   * Top-level anchor for the supervision hierarchy of this actor system. Will
   * receive only Supervise/ChildTerminated system messages or Failure message.
   */
  private[akka] val theOneWhoWalksTheBubblesOfSpaceTime: InternalActorRef = new MinimalActorRef {
    val causeOfTermination: Promise[Terminated] = Promise[Terminated]()

    val path = rootPath / "bubble-walker"

    def provider: ActorRefProvider = LocalActorRefProvider.this

    def isWalking = causeOfTermination.future.isCompleted == false

    override def stop(): Unit = {
      causeOfTermination.trySuccess(Terminated(provider.rootGuardian)(existenceConfirmed = true, addressTerminated = true)) //Idempotent
      terminationPromise.tryCompleteWith(causeOfTermination.future) // Signal termination downstream, idempotent
    }

    @deprecated("Use context.watch(actor) and receive Terminated(actor)", "2.2")
    override private[akka] def isTerminated: Boolean = !isWalking

    override def !(message: Any)(implicit sender: ActorRef = Actor.noSender): Unit =
      if (isWalking)
        message match {
          case null ⇒ throw new InvalidMessageException("Message is null")
          case _    ⇒ log.error(s"$this received unexpected message [$message]")
        }

    override def sendSystemMessage(message: SystemMessage): Unit = if (isWalking) {
      message match {
        case Failed(child: InternalActorRef, ex, _) ⇒
          log.error(ex, s"guardian $child failed, shutting down!")
          causeOfTermination.tryFailure(ex)
          child.stop()
        case Supervise(_, _)           ⇒ // TODO register child in some map to keep track of it and enable shutdown after all dead
        case _: DeathWatchNotification ⇒ stop()
        case _                         ⇒ log.error(s"$this received unexpected system message [$message]")
      }
    }
  }

  /*
   * The problem is that ActorRefs need a reference to the ActorSystem to
   * provide their service. Hence they cannot be created while the
   * constructors of ActorSystem and ActorRefProvider are still running.
   * The solution is to split out that last part into an init() method,
   * but it also requires these references to be @volatile and lazy.
   */
  @volatile
  private var system: ActorSystemImpl = _

  @volatile
  private var extraNames: Map[String, InternalActorRef] = Map()

  /**
   * Higher-level providers (or extensions) might want to register new synthetic
   * top-level paths for doing special stuff. This is the way to do just that.
   * Just be careful to complete all this before ActorSystem.start() finishes,
   * or before you start your own auto-spawned actors.
   */
  def registerExtraNames(_extras: Map[String, InternalActorRef]): Unit = extraNames ++= _extras

  private def guardianSupervisorStrategyConfigurator =
    dynamicAccess.createInstanceFor[SupervisorStrategyConfigurator](settings.SupervisorStrategyClass, EmptyImmutableSeq).get

  /**
   * Overridable supervision strategy to be used by the “/user” guardian.
   */
  protected def rootGuardianStrategy: SupervisorStrategy = OneForOneStrategy() {
    case ex ⇒
      log.error(ex, "guardian failed, shutting down system")
      SupervisorStrategy.Stop
  }

  /**
   * Overridable supervision strategy to be used by the “/user” guardian.
   */
  protected def guardianStrategy: SupervisorStrategy = guardianSupervisorStrategyConfigurator.create()

  /**
   * Overridable supervision strategy to be used by the “/user” guardian.
   */
  protected def systemGuardianStrategy: SupervisorStrategy = SupervisorStrategy.defaultStrategy

  private lazy val defaultDispatcher = system.dispatchers.defaultGlobalDispatcher

  private lazy val defaultMailbox = system.mailboxes.lookup(Mailboxes.DefaultMailboxId)

  override lazy val rootGuardian: LocalActorRef =
    new LocalActorRef(
      system,
      Props(classOf[LocalActorRefProvider.Guardian], rootGuardianStrategy),
      defaultDispatcher,
      defaultMailbox,
      theOneWhoWalksTheBubblesOfSpaceTime,
      rootPath) {
      override def getParent: InternalActorRef = this
      override def getSingleChild(name: String): InternalActorRef = name match {
        case "temp"        ⇒ tempContainer
        case "deadLetters" ⇒ deadLetters
        case other         ⇒ extraNames.get(other).getOrElse(super.getSingleChild(other))
      }
    }

  override def rootGuardianAt(address: Address): ActorRef =
    if (address == rootPath.address) rootGuardian
    else deadLetters

  override lazy val guardian: LocalActorRef = {
    val cell = rootGuardian.underlying
    cell.reserveChild("user")
    val ref = new LocalActorRef(system, system.guardianProps.getOrElse(Props(classOf[LocalActorRefProvider.Guardian], guardianStrategy)),
      defaultDispatcher, defaultMailbox, rootGuardian, rootPath / "user")
    cell.initChild(ref)
    ref.start()
    ref
  }

  override lazy val systemGuardian: LocalActorRef = {
    val cell = rootGuardian.underlying
    cell.reserveChild("system")
    val ref = new LocalActorRef(
      system, Props(classOf[LocalActorRefProvider.SystemGuardian], systemGuardianStrategy, guardian),
      defaultDispatcher, defaultMailbox, rootGuardian, rootPath / "system")
    cell.initChild(ref)
    ref.start()
    ref
  }

  lazy val tempContainer = new VirtualPathContainer(system.provider, tempNode, rootGuardian, log)

  def registerTempActor(actorRef: InternalActorRef, path: ActorPath): Unit = {
    assert(path.parent eq tempNode, "cannot registerTempActor() with anything not obtained from tempPath()")
    tempContainer.addChild(path.name, actorRef)
  }

  def unregisterTempActor(path: ActorPath): Unit = {
    assert(path.parent eq tempNode, "cannot unregisterTempActor() with anything not obtained from tempPath()")
    tempContainer.removeChild(path.name)
  }

  def init(_system: ActorSystemImpl) {
    system = _system
    rootGuardian.start()
    // chain death watchers so that killing guardian stops the application
    systemGuardian.sendSystemMessage(Watch(guardian, systemGuardian))
    rootGuardian.sendSystemMessage(Watch(systemGuardian, rootGuardian))
    eventStream.startDefaultLoggers(_system)
  }

  @deprecated("use actorSelection instead of actorFor", "2.2")
  private[akka] override def actorFor(ref: InternalActorRef, path: String): InternalActorRef = path match {
    case RelativeActorPath(elems) ⇒
      if (elems.isEmpty) {
        log.debug("look-up of empty path string [{}] fails (per definition)", path)
        deadLetters
      } else if (elems.head.isEmpty) actorFor(rootGuardian, elems.tail)
      else actorFor(ref, elems)
    case ActorPathExtractor(address, elems) if address == rootPath.address ⇒ actorFor(rootGuardian, elems)
    case _ ⇒
      log.debug("look-up of unknown path [{}] failed", path)
      deadLetters
  }

  @deprecated("use actorSelection instead of actorFor", "2.2")
  private[akka] override def actorFor(path: ActorPath): InternalActorRef =
    if (path.root == rootPath) actorFor(rootGuardian, path.elements)
    else {
      log.debug("look-up of foreign ActorPath [{}] failed", path)
      deadLetters
    }

  @deprecated("use actorSelection instead of actorFor", "2.2")
  private[akka] override def actorFor(ref: InternalActorRef, path: Iterable[String]): InternalActorRef =
    if (path.isEmpty) {
      log.debug("look-up of empty path sequence fails (per definition)")
      deadLetters
    } else ref.getChild(path.iterator) match {
      case Nobody ⇒
        log.debug("look-up of path sequence [/{}] failed", path.mkString("/"))
        new EmptyLocalActorRef(system.provider, ref.path / path, eventStream)
      case x ⇒ x
    }

  def resolveActorRef(path: String): ActorRef = path match {
    case ActorPathExtractor(address, elems) if address == rootPath.address ⇒ resolveActorRef(rootGuardian, elems)
    case _ ⇒
      log.debug("resolve of unknown path [{}] failed", path)
      deadLetters
  }

  def resolveActorRef(path: ActorPath): ActorRef = {
    if (path.root == rootPath) resolveActorRef(rootGuardian, path.elements)
    else {
      log.debug("resolve of foreign ActorPath [{}] failed", path)
      deadLetters
    }
  }

  /**
   * INTERNAL API
   */
  private[akka] def resolveActorRef(ref: InternalActorRef, pathElements: Iterable[String]): InternalActorRef =
    if (pathElements.isEmpty) {
      log.debug("resolve of empty path sequence fails (per definition)")
      deadLetters
    } else ref.getChild(pathElements.iterator) match {
      case Nobody ⇒
        log.debug("resolve of path sequence [/{}] failed", pathElements.mkString("/"))
        new EmptyLocalActorRef(system.provider, ref.path / pathElements, eventStream)
      case x ⇒ x
    }

  def actorOf(system: ActorSystemImpl, props: Props, supervisor: InternalActorRef, path: ActorPath,
              systemService: Boolean, deploy: Option[Deploy], lookupDeploy: Boolean, async: Boolean): InternalActorRef = {
    props.deploy.routerConfig match {
      case NoRouter ⇒
        if (settings.DebugRouterMisconfiguration) {
          deployer.lookup(path) foreach { d ⇒
            if (d.routerConfig != NoRouter)
              log.warning("Configuration says that [{}] should be a router, but code disagrees. Remove the config or add a routerConfig to its Props.", path)
          }
        }

        val props2 =
          // mailbox and dispatcher defined in deploy should override props
          (if (lookupDeploy) deployer.lookup(path) else deploy) match {
            case Some(d) ⇒
              (d.dispatcher, d.mailbox) match {
                case (Deploy.NoDispatcherGiven, Deploy.NoMailboxGiven) ⇒ props
                case (dsp, Deploy.NoMailboxGiven)                      ⇒ props.withDispatcher(dsp)
                case (Deploy.NoMailboxGiven, mbx)                      ⇒ props.withMailbox(mbx)
                case (dsp, mbx)                                        ⇒ props.withDispatcher(dsp).withMailbox(mbx)
              }
            case _ ⇒ props // no deployment config found
          }

        if (!system.dispatchers.hasDispatcher(props2.dispatcher))
          throw new ConfigurationException(s"Dispatcher [${props2.dispatcher}] not configured for path $path")

        try {
          val dispatcher = system.dispatchers.lookup(props2.dispatcher)
          val mailboxType = system.mailboxes.getMailboxType(props2, dispatcher.configurator.config)

          if (async) new RepointableActorRef(system, props2, dispatcher, mailboxType, supervisor, path).initialize(async)
          else new LocalActorRef(system, props2, dispatcher, mailboxType, supervisor, path)
        } catch {
          case NonFatal(e) ⇒ throw new ConfigurationException(
            s"configuration problem while creating [$path] with dispatcher [${props2.dispatcher}] and mailbox [${props2.mailbox}]", e)
        }

      case router ⇒
        val lookup = if (lookupDeploy) deployer.lookup(path) else None
        val r = router :: deploy.map(_.routerConfig).toList ::: lookup.map(_.routerConfig).toList reduce ((a, b) ⇒ b withFallback a)
        val p = props.withRouter(r)

        if (!system.dispatchers.hasDispatcher(p.dispatcher))
          throw new ConfigurationException(s"Dispatcher [${p.dispatcher}] not configured for routees of $path")
        if (!system.dispatchers.hasDispatcher(r.routerDispatcher))
          throw new ConfigurationException(s"Dispatcher [${p.dispatcher}] not configured for router of $path")

        val routerProps = Props(
          p.deploy.copy(dispatcher = p.routerConfig.routerDispatcher),
          classOf[RoutedActorCell.RouterActorCreator], Vector(p.routerConfig))
        val routeeProps = p.withRouter(NoRouter)

        try {
          val routerDispatcher = system.dispatchers.lookup(p.routerConfig.routerDispatcher)
          val routerMailbox = system.mailboxes.getMailboxType(routerProps, routerDispatcher.configurator.config)

          // routers use context.actorOf() to create the routees, which does not allow us to pass
          // these through, but obtain them here for early verification
          val routeeDispatcher = system.dispatchers.lookup(p.dispatcher)
          val routeeMailbox = system.mailboxes.getMailboxType(routeeProps, routeeDispatcher.configurator.config)

          new RoutedActorRef(system, routerProps, routerDispatcher, routerMailbox, routeeProps, supervisor, path).initialize(async)
        } catch {
          case NonFatal(e) ⇒ throw new ConfigurationException(
            s"configuration problem while creating [$path] with router dispatcher [${routerProps.dispatcher}] and mailbox [${routerProps.mailbox}] " +
              s"and routee dispatcher [${routeeProps.dispatcher}] and mailbox [${routeeProps.mailbox}]", e)
        }
    }
  }

  def getExternalAddressFor(addr: Address): Option[Address] = if (addr == rootPath.address) Some(addr) else None

  def getDefaultAddress: Address = rootPath.address
}

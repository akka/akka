/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import java.util.concurrent.atomic.AtomicLong
import akka.dispatch._
import akka.routing._
import akka.AkkaException
import akka.event._
import akka.util.{ NonFatal, Switch, Helpers }

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
   * Reference to the supervisor used for all top-level user actors.
   */
  def guardian: InternalActorRef

  /**
   * Reference to the supervisor used for all top-level system actors.
   */
  def systemGuardian: InternalActorRef

  /**
   * Dead letter destination for this provider.
   */
  def deadLetters: ActorRef

  /**
   * The root path for all actors within this actor system, including remote
   * address if enabled.
   */
  def rootPath: ActorPath

  /**
   * The Settings associated with this ActorRefProvider
   */
  def settings: ActorSystem.Settings

  //FIXME WHY IS THIS HERE?
  def dispatcher: MessageDispatcher

  /**
   * Initialization of an ActorRefProvider happens in two steps: first
   * construction of the object with settings, eventStream, scheduler, etc.
   * and then—when the ActorSystem is constructed—the second phase during
   * which actors may be created (e.g. the guardians).
   */
  def init(system: ActorSystemImpl): Unit

  /**
   * The Deployer associated with this ActorRefProvider
   */
  def deployer: Deployer

  //FIXME WHY IS THIS HERE?
  def scheduler: Scheduler

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
    system: ActorSystemImpl,
    props: Props,
    supervisor: InternalActorRef,
    path: ActorPath,
    systemService: Boolean,
    deploy: Option[Deploy],
    lookupDeploy: Boolean): InternalActorRef

  /**
   * Create actor reference for a specified local or remote path. If no such
   * actor exists, it will be (equivalent to) a dead letter reference.
   */
  def actorFor(path: ActorPath): InternalActorRef

  /**
   * Create actor reference for a specified local or remote path, which will
   * be parsed using java.net.URI. If no such actor exists, it will be
   * (equivalent to) a dead letter reference. If `s` is a relative URI, resolve
   * it relative to the given ref.
   */
  def actorFor(ref: InternalActorRef, s: String): InternalActorRef

  /**
   * Create actor reference for the specified child path starting at the
   * given starting point. This method always returns an actor which is “logically local”,
   * i.e. it cannot be used to obtain a reference to an actor which is not
   * physically or logically attached to this actor system.
   */
  def actorFor(ref: InternalActorRef, p: Iterable[String]): InternalActorRef

  /**
   * This Future is completed upon termination of this ActorRefProvider, which
   * is usually initiated by stopping the guardian via ActorSystem.stop().
   */
  def terminationFuture: Future[Unit]

  //FIXME I PROPOSE TO REMOVE THIS IN 2.1 - √
  /**
   * Obtain the address which is to be used within sender references when
   * sending to the given other address or none if the other address cannot be
   * reached from this system (i.e. no means of communication known; no
   * attempt is made to verify actual reachability).
   */
  def getExternalAddressFor(addr: Address): Option[Address]
}

/**
 * Interface implemented by ActorSystem and ActorContext, the only two places
 * from which you can get fresh actors.
 */
trait ActorRefFactory {
  /**
   * INTERNAL USE ONLY
   */
  protected def systemImpl: ActorSystemImpl
  /**
   * INTERNAL USE ONLY
   */
  protected def provider: ActorRefProvider

  /**
   * Returns the default MessageDispatcher associated with this ActorRefFactory
   */
  implicit def dispatcher: MessageDispatcher

  /**
   * Father of all children created by this interface.
   *
   * INTERNAL USE ONLY
   */
  protected def guardian: InternalActorRef

  /**
   * INTERNAL USE ONLY
   */
  protected def lookupRoot: InternalActorRef

  /**
   * Create new actor as child of this context and give it an automatically
   * generated name (currently similar to base64-encoded integer count,
   * reversed and with “$” prepended, may change in the future).
   *
   * See [[akka.actor.Props]] for details on how to obtain a `Props` object.
   *
   * When invoked on ActorSystem, this method sends a message to the guardian
   * actor and blocks waiting for a reply, see `akka.actor.creation-timeout` in
   * the `reference.conf`.
   */
  def actorOf(props: Props): ActorRef

  /**
   * Create new actor as child of this context with the given name, which must
   * not be null, empty or start with “$”. If the given name is already in use,
   * and `InvalidActorNameException` is thrown.
   *
   * See [[akka.actor.Props]] for details on how to obtain a `Props` object.
   *
   * When invoked on ActorSystem, this method sends a message to the guardian
   * actor and blocks waiting for a reply, see `akka.actor.creation-timeout` in
   * the `reference.conf`.
   */
  def actorOf(props: Props, name: String): ActorRef

  /**
   * Look-up an actor by path; if it does not exist, returns a reference to
   * the dead-letter mailbox of the [[akka.actor.ActorSystem]]. If the path
   * point to an actor which is not local, no attempt is made during this
   * call to verify that the actor it represents does exist or is alive; use
   * `watch(ref)` to be notified of the target’s termination, which is also
   * signaled if the queried path cannot be resolved.
   */
  def actorFor(path: ActorPath): ActorRef = provider.actorFor(path)

  /**
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
   */
  def actorFor(path: String): ActorRef = provider.actorFor(lookupRoot, path)

  /**
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
   * }
   * }
   * }}}
   *
   * For maximum performance use a collection with efficient head & tail operations.
   */
  def actorFor(path: Iterable[String]): ActorRef = provider.actorFor(lookupRoot, path)

  /**
   * ''Java API'': Look-up an actor by applying the given path elements, starting from the
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
   *     final ActorRef target = context().actorFor(path);
   *     ...
   * }
   * }
   * }}}
   *
   * For maximum performance use a collection with efficient head & tail operations.
   */
  def actorFor(path: java.lang.Iterable[String]): ActorRef = {
    import scala.collection.JavaConverters._
    provider.actorFor(lookupRoot, path.asScala)
  }

  /**
   * Construct an [[akka.actor.ActorSelection]] from the given path, which is
   * parsed for wildcards (these are replaced by regular expressions
   * internally). No attempt is made to verify the existence of any part of
   * the supplied path, it is recommended to send a message and gather the
   * replies in order to resolve the matching set of actors.
   */
  def actorSelection(path: String): ActorSelection = ActorSelection(lookupRoot, path)

  /**
   * Stop the actor pointed to by the given [[akka.actor.ActorRef]]; this is
   * an asynchronous operation, i.e. involves a message send.
   * When invoked on [[akka.actor.ActorSystem]] for a top-level actor, this
   * method sends a message to the guardian actor and blocks waiting for a reply,
   * see `akka.actor.creation-timeout` in the `reference.conf`.
   */
  def stop(actor: ActorRef): Unit
}

/**
 * Internal Akka use only, used in implementation of system.actorOf.
 */
private[akka] case class CreateChild(props: Props, name: String)

/**
 * Internal Akka use only, used in implementation of system.actorOf.
 */
private[akka] case class CreateRandomNameChild(props: Props)

/**
 * Internal Akka use only, used in implementation of system.stop(child).
 */
private[akka] case class StopChild(child: ActorRef)

/**
 * Local ActorRef provider.
 */
class LocalActorRefProvider(
  _systemName: String,
  override val settings: ActorSystem.Settings,
  val eventStream: EventStream,
  override val scheduler: Scheduler,
  override val deployer: Deployer) extends ActorRefProvider {

  // this is the constructor needed for reflectively instantiating the provider
  def this(_systemName: String,
           settings: ActorSystem.Settings,
           eventStream: EventStream,
           scheduler: Scheduler,
           dynamicAccess: DynamicAccess) =
    this(_systemName,
      settings,
      eventStream,
      scheduler,
      new Deployer(settings, dynamicAccess))

  override val rootPath: ActorPath = RootActorPath(Address("akka", _systemName))

  private[akka] val log: LoggingAdapter = Logging(eventStream, "LocalActorRefProvider(" + rootPath.address + ")")

  override val deadLetters: InternalActorRef = new DeadLetterActorRef(this, rootPath / "deadLetters", eventStream)

  /*
   * generate name for temporary actor refs
   */
  private val tempNumber = new AtomicLong

  private def tempName() = Helpers.base64(tempNumber.getAndIncrement())

  private val tempNode = rootPath / "temp"

  override def tempPath(): ActorPath = tempNode / tempName()

  /**
   * Top-level anchor for the supervision hierarchy of this actor system. Will
   * receive only Supervise/ChildTerminated system messages or Failure message.
   */
  private[akka] val theOneWhoWalksTheBubblesOfSpaceTime: InternalActorRef = new MinimalActorRef {
    val stopped = new Switch(false)

    @volatile
    var causeOfTermination: Option[Throwable] = None

    val path = rootPath / "bubble-walker"

    def provider: ActorRefProvider = LocalActorRefProvider.this

    override def stop(): Unit = stopped switchOn {
      terminationFuture.complete(causeOfTermination.toLeft(()))
    }

    override def isTerminated: Boolean = stopped.isOn

    override def !(message: Any)(implicit sender: ActorRef = null): Unit = stopped.ifOff(message match {
      case Failed(ex) if sender ne null ⇒ causeOfTermination = Some(ex); sender.asInstanceOf[InternalActorRef].stop()
      case _                            ⇒ log.error(this + " received unexpected message [" + message + "]")
    })

    override def sendSystemMessage(message: SystemMessage): Unit = stopped ifOff {
      message match {
        case Supervise(_)       ⇒ // TODO register child in some map to keep track of it and enable shutdown after all dead
        case ChildTerminated(_) ⇒ stop()
        case _                  ⇒ log.error(this + " received unexpected system message [" + message + "]")
      }
    }
  }

  /**
   * Overridable supervision strategy to be used by the “/user” guardian.
   */
  protected def guardianSupervisionStrategy: SupervisorStrategy = {
    import akka.actor.SupervisorStrategy._
    OneForOneStrategy() {
      case _: ActorKilledException         ⇒ Stop
      case _: ActorInitializationException ⇒ Stop
      case _: Exception                    ⇒ Restart
    }
  }

  /*
   * Guardians can be asked by ActorSystem to create children, i.e. top-level
   * actors. Therefore these need to answer to these requests, forwarding any
   * exceptions which might have occurred.
   */
  private class Guardian extends Actor {

    override val supervisorStrategy: SupervisorStrategy = guardianSupervisionStrategy

    def receive = {
      case Terminated(_)                ⇒ context.stop(self)
      case CreateChild(child, name)     ⇒ sender ! (try context.actorOf(child, name) catch { case NonFatal(e) ⇒ e }) // FIXME shouldn't this use NonFatal & Status.Failure?
      case CreateRandomNameChild(child) ⇒ sender ! (try context.actorOf(child) catch { case NonFatal(e) ⇒ e }) // FIXME shouldn't this use NonFatal & Status.Failure?
      case StopChild(child)             ⇒ context.stop(child); sender ! "ok"
      case m                            ⇒ deadLetters ! DeadLetter(m, sender, self)
    }

    // guardian MUST NOT lose its children during restart
    override def preRestart(cause: Throwable, msg: Option[Any]) {}
  }

  /**
   * Overridable supervision strategy to be used by the “/system” guardian.
   */
  protected def systemGuardianSupervisionStrategy: SupervisorStrategy = {
    import akka.actor.SupervisorStrategy._
    OneForOneStrategy() {
      case _: ActorKilledException | _: ActorInitializationException ⇒ Stop
      case _: Exception ⇒ Restart
    }
  }

  /*
   * Guardians can be asked by ActorSystem to create children, i.e. top-level
   * actors. Therefore these need to answer to these requests, forwarding any
   * exceptions which might have occurred.
   */
  private class SystemGuardian extends Actor {

    override val supervisorStrategy: SupervisorStrategy = systemGuardianSupervisionStrategy

    def receive = {
      case Terminated(_)                ⇒ eventStream.stopDefaultLoggers(); context.stop(self)
      case CreateChild(child, name)     ⇒ sender ! (try context.actorOf(child, name) catch { case e: Exception ⇒ e }) // FIXME shouldn't this use NonFatal & Status.Failure?
      case CreateRandomNameChild(child) ⇒ sender ! (try context.actorOf(child) catch { case e: Exception ⇒ e }) // FIXME shouldn't this use NonFatal & Status.Failure?
      case StopChild(child)             ⇒ context.stop(child); sender ! "ok"
      case m                            ⇒ deadLetters ! DeadLetter(m, sender, self)
    }

    // guardian MUST NOT lose its children during restart
    override def preRestart(cause: Throwable, msg: Option[Any]) {}
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

  def dispatcher: MessageDispatcher = system.dispatcher

  lazy val terminationFuture: Promise[Unit] = Promise[Unit]()(dispatcher)

  @volatile
  private var extraNames: Map[String, InternalActorRef] = Map()

  /**
   * Higher-level providers (or extensions) might want to register new synthetic
   * top-level paths for doing special stuff. This is the way to do just that.
   * Just be careful to complete all this before ActorSystem.start() finishes,
   * or before you start your own auto-spawned actors.
   */
  def registerExtraNames(_extras: Map[String, InternalActorRef]): Unit = extraNames ++= _extras

  private val guardianProps = Props(new Guardian)

  lazy val rootGuardian: InternalActorRef =
    new LocalActorRef(system, guardianProps, theOneWhoWalksTheBubblesOfSpaceTime, rootPath, true) {
      object Extra {
        def unapply(s: String): Option[InternalActorRef] = extraNames.get(s)
      }

      override def getParent: InternalActorRef = this

      override def getSingleChild(name: String): InternalActorRef = {
        name match {
          case "temp"   ⇒ tempContainer
          case Extra(e) ⇒ e
          case _        ⇒ super.getSingleChild(name)
        }
      }
    }

  lazy val guardian: InternalActorRef =
    actorOf(system, guardianProps, rootGuardian, rootPath / "user", true, None, false)

  lazy val systemGuardian: InternalActorRef =
    actorOf(system, guardianProps.withCreator(new SystemGuardian), rootGuardian, rootPath / "system", true, None, false)

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
    // chain death watchers so that killing guardian stops the application
    guardian.sendSystemMessage(Watch(systemGuardian, guardian))
    rootGuardian.sendSystemMessage(Watch(rootGuardian, systemGuardian))
    eventStream.startDefaultLoggers(_system)
  }

  def actorFor(ref: InternalActorRef, path: String): InternalActorRef = path match {
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

  def actorFor(path: ActorPath): InternalActorRef =
    if (path.root == rootPath) actorFor(rootGuardian, path.elements)
    else {
      log.debug("look-up of foreign ActorPath [{}] failed", path)
      deadLetters
    }

  def actorFor(ref: InternalActorRef, path: Iterable[String]): InternalActorRef =
    if (path.isEmpty) {
      log.debug("look-up of empty path sequence fails (per definition)")
      deadLetters
    } else ref.getChild(path.iterator) match {
      case Nobody ⇒
        log.debug("look-up of path sequence [/{}] failed", path.mkString("/"))
        new EmptyLocalActorRef(system.provider, ref.path / path, eventStream)
      case x ⇒ x
    }

  def actorOf(system: ActorSystemImpl, props: Props, supervisor: InternalActorRef, path: ActorPath,
              systemService: Boolean, deploy: Option[Deploy], lookupDeploy: Boolean): InternalActorRef = {
    props.routerConfig match {
      case NoRouter ⇒ new LocalActorRef(system, props, supervisor, path, systemService) // create a local actor
      case router ⇒
        val lookup = if (lookupDeploy) deployer.lookup(path) else None
        val fromProps = Iterator(props.deploy.copy(routerConfig = props.deploy.routerConfig withFallback router))
        val d = fromProps ++ deploy.iterator ++ lookup.iterator reduce ((a, b) ⇒ b withFallback a)
        new RoutedActorRef(system, props.withRouter(d.routerConfig), supervisor, path)
    }
  }

  def getExternalAddressFor(addr: Address): Option[Address] = if (addr == rootPath.address) Some(addr) else None
}
